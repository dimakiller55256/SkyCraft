#include "LoopbackTransport.h"

#include "Net.h"

namespace link
{
	namespace
	{
		constexpr std::size_t kMaxMessage = 1 << 20;
		constexpr std::size_t kMaxQueued = 64u << 20;  // more than this unsent: the peer isn't reading
	}

	std::unique_ptr<LoopbackTransport> LoopbackTransport::Host(std::uint16_t a_port, std::string& a_error)
	{
		std::unique_ptr<LoopbackTransport> t(new LoopbackTransport());
		t->listening_ = net::Listen(a_port, &t->port_);
		if (t->listening_ == INVALID_SOCKET) {
			a_error = "can't listen on 127.0.0.1:" + std::to_string(a_port) + ": " + net::ErrorText(::WSAGetLastError());
			return nullptr;
		}
		return t;
	}

	std::unique_ptr<LoopbackTransport> LoopbackTransport::Guest(std::uint16_t a_port)
	{
		std::unique_ptr<LoopbackTransport> t(new LoopbackTransport());
		t->hostPort_ = a_port;
		return t;
	}

	LoopbackTransport::~LoopbackTransport()
	{
		for (auto& [peer, link] : links_) {
			net::Close(link.socket);
		}
		net::Close(listening_);
	}

	PeerId LoopbackTransport::HostPeer()
	{
		if (listening_ != INVALID_SOCKET) {
			return 0;
		}
		const auto it = links_.find(kHost);
		return it != links_.end() && !it->second.connecting ? kHost : 0;
	}

	std::string LoopbackTransport::Name(PeerId a_peer) { return a_peer == kHost ? "host" : "guest-" + std::to_string(a_peer - 0x1000 + 1); }

	bool LoopbackTransport::Send(PeerId a_peer, int a_channel, std::span<const std::uint8_t> a_data, bool)
	{
		auto it = links_.find(a_peer);
		if (it == links_.end() || it->second.socket == INVALID_SOCKET || a_data.size() > kMaxMessage) {
			return true;  // nobody there: the data goes nowhere, as a lost datagram would
		}
		auto& out = it->second.out;
		if (out.size() - it->second.outSent > kMaxQueued) {
			return false;
		}
		const auto length = static_cast<std::uint32_t>(a_data.size() + 1);
		for (int i = 0; i < 4; ++i) {
			out.push_back(static_cast<std::uint8_t>(length >> (8 * i)));
		}
		out.push_back(static_cast<std::uint8_t>(a_channel));
		out.insert(out.end(), a_data.begin(), a_data.end());
		WriteLink(it->second);
		return true;
	}

	bool LoopbackTransport::WriteLink(Link& a_link)
	{
		if (a_link.connecting || a_link.socket == INVALID_SOCKET) {
			return true;
		}
		while (a_link.outSent < a_link.out.size()) {
			const int chunk = static_cast<int>(std::min<std::size_t>(a_link.out.size() - a_link.outSent, 1 << 20));
			const int sent = ::send(a_link.socket, reinterpret_cast<const char*>(a_link.out.data() + a_link.outSent), chunk, 0);
			if (sent <= 0) {
				return ::WSAGetLastError() == WSAEWOULDBLOCK;
			}
			a_link.outSent += static_cast<std::size_t>(sent);
		}
		a_link.out.clear();
		a_link.outSent = 0;
		return true;
	}

	void LoopbackTransport::ReadLink(PeerId a_peer, Link& a_link, std::vector<Message>& a_out)
	{
		std::uint8_t buffer[64 * 1024];
		for (;;) {
			const int got = ::recv(a_link.socket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
			if (got == 0 || (got < 0 && ::WSAGetLastError() != WSAEWOULDBLOCK)) {
				Drop(a_peer);
				return;
			}
			if (got < 0) {
				break;
			}
			a_link.in.insert(a_link.in.end(), buffer, buffer + got);
		}
		std::size_t at = 0;
		while (a_link.in.size() - at >= 5) {
			const auto* p = a_link.in.data() + at;
			const std::uint32_t length = p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<std::uint32_t>(p[3]) << 24);
			if (length == 0 || length > kMaxMessage + 1) {
				Drop(a_peer);
				return;
			}
			if (a_link.in.size() - at < 4 + length) {
				break;
			}
			Message m{ a_peer, p[4], std::vector<std::uint8_t>(p + 5, p + 4 + length) };
			a_out.push_back(std::move(m));
			at += 4 + length;
		}
		a_link.in.erase(a_link.in.begin(), a_link.in.begin() + static_cast<std::ptrdiff_t>(at));
	}

	void LoopbackTransport::Drop(PeerId a_peer)
	{
		auto it = links_.find(a_peer);
		if (it == links_.end()) {
			return;
		}
		net::Close(it->second.socket);
		links_.erase(it);
		lost_.push_back(a_peer);
	}

	void LoopbackTransport::Pump(std::vector<Message>& a_out)
	{
		const auto now = std::chrono::steady_clock::now();
		if (listening_ != INVALID_SOCKET) {
			for (;;) {
				SOCKET s = ::accept(listening_, nullptr, nullptr);
				if (s == INVALID_SOCKET) {
					break;
				}
				u_long on = 1;
				::ioctlsocket(s, FIONBIO, &on);
				links_[nextGuest_++].socket = s;
			}
		} else if (links_.empty() && now >= retryAt_) {
			retryAt_ = now + std::chrono::seconds(1);
			SOCKET s = net::StartConnect(hostPort_);
			if (s != INVALID_SOCKET) {
				auto& link = links_[kHost];
				link.socket = s;
				link.connecting = true;
				link.since = now;
			}
		}
		std::vector<PeerId> peers;
		for (auto& [peer, link] : links_) {
			peers.push_back(peer);
		}
		for (const PeerId peer : peers) {
			auto it = links_.find(peer);
			if (it == links_.end()) {
				continue;
			}
			auto& link = it->second;
			if (link.connecting) {
				WSAPOLLFD  fd{ link.socket, POLLWRNORM, 0 };
				const bool ready = ::WSAPoll(&fd, 1, 0) > 0;
				// Older Windows 10 never reports a refused connect through WSAPoll: the socket's error does.
				if (net::PendingError(link.socket) != 0 || (ready && (fd.revents & (POLLERR | POLLHUP))) || now - link.since > std::chrono::seconds(3)) {
					net::Close(link.socket);
					links_.erase(it);  // not up yet: try again later, nothing was lost
					continue;
				}
				if (!ready) {
					continue;
				}
				link.connecting = false;
			}
			ReadLink(peer, link, a_out);
			if (auto again = links_.find(peer); again != links_.end() && !WriteLink(again->second)) {
				Drop(peer);
			}
		}
	}

	void LoopbackTransport::AddSockets(std::vector<WSAPOLLFD>& a_fds)
	{
		if (listening_ != INVALID_SOCKET) {
			a_fds.push_back({ listening_, POLLRDNORM, 0 });
		}
		for (auto& [peer, link] : links_) {
			if (link.socket != INVALID_SOCKET) {
				a_fds.push_back({ link.socket, static_cast<SHORT>(link.connecting || !link.out.empty() ? POLLWRNORM | POLLRDNORM : POLLRDNORM), 0 });
			}
		}
	}

	void LoopbackTransport::TakeLost(std::vector<PeerId>& a_out)
	{
		a_out.insert(a_out.end(), lost_.begin(), lost_.end());
		lost_.clear();
	}

	void LoopbackTransport::Forget(PeerId a_peer)
	{
		if (auto it = links_.find(a_peer); it != links_.end()) {
			net::Close(it->second.socket);
			links_.erase(it);
		}
	}
}
