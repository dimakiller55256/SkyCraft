#include "Tunnel.h"

#include "Net.h"
#include "Status.h"

#include <algorithm>

namespace link
{
	namespace
	{
		constexpr std::uint8_t kRoleHost = 1;
		constexpr std::uint8_t kRoleGuest = 2;
		constexpr int          kReadBudget = 64;         // chunks per socket per pump: a busy stream can't starve the rest
		constexpr int          kDatagramBudget = 256;    // likewise for datagrams
		constexpr std::size_t  kMaxBacklog = 64u << 20;  // for a local program that stopped reading: give up on it
		constexpr auto         kClosingWait = std::chrono::seconds(10);
		constexpr auto         kConnectWait = std::chrono::seconds(10);

		void NoDelay(SOCKET a_socket)
		{
			BOOL on = TRUE;
			::setsockopt(a_socket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&on), sizeof(on));
		}
	}

	Tunnel::Tunnel(Transport& a_transport, TunnelOptions a_options) :
		transport_(a_transport), options_(std::move(a_options))
	{}

	Tunnel::~Tunnel()
	{
		for (auto& [id, peer] : peers_) {
			Reset(peer);
		}
		net::Close(minecraftListen_);
		net::Close(together_);
	}

	std::size_t Tunnel::Peers() const
	{
		return static_cast<std::size_t>(std::ranges::count_if(peers_, [](const auto& a_entry) { return a_entry.second.hello; }));
	}

	std::size_t Tunnel::Streams() const
	{
		std::size_t count = 0;
		for (const auto& [id, peer] : peers_) {
			count += peer.streams.size();
		}
		return count;
	}

	void Tunnel::Pump(Clock::time_point a_now)
	{
		inbox_.clear();
		transport_.Pump(inbox_);
		if (stopping_) {
			for (auto& [id, peer] : peers_) {
				Flush(id, peer, a_now);
			}
			return;
		}
		for (const auto& message : inbox_) {
			OnMessage(message, a_now);
		}
		lost_.clear();
		transport_.TakeLost(lost_);
		for (const PeerId id : lost_) {
			Drop(id, "lost");
		}
		if (!options_.host && !finished_) {
			if (host_ == 0) {
				if (const PeerId host = transport_.HostPeer(); host != 0) {
					host_ = host;
					helloAt_ = a_now;
					Peer& peer = peers_[host];
					peer.heard = a_now;
					Queue(host, peer, EncodeHello(kRoleGuest, options_.name), a_now);
				}
			} else if (const auto it = peers_.find(host_); it != peers_.end() && !it->second.hello && a_now - helloAt_ > kHelloWait) {
				Fail("no_answer", "Хост не ответил: у него не запущен SkyCraft Link или старая версия мода.");
			}
			AcceptLocal(a_now);
		}
		PumpDatagrams();
		std::vector<PeerId> silent;
		for (auto& [id, peer] : peers_) {
			PumpStreams(id, peer, a_now);
			if (peer.hello && peer.reliable.empty() && a_now - peer.sent >= kKeepAlive) {
				Queue(id, peer, EncodeKeepAlive(), a_now);
			}
			Flush(id, peer, a_now);
			// A guest waits for its host's hello separately (no_answer).
			if ((peer.hello || options_.host) && a_now - peer.heard > kSilence) {
				silent.push_back(id);
			}
		}
		for (const PeerId id : silent) {
			Drop(id, "silent");
		}
	}

	void Tunnel::AddSockets(std::vector<WSAPOLLFD>& a_fds) const
	{
		if (minecraftListen_ != INVALID_SOCKET) {
			a_fds.push_back({ minecraftListen_, POLLRDNORM, 0 });
		}
		if (together_ != INVALID_SOCKET) {
			a_fds.push_back({ together_, POLLRDNORM, 0 });
		}
		for (const auto& [id, peer] : peers_) {
			if (peer.udp != INVALID_SOCKET) {
				a_fds.push_back({ peer.udp, POLLRDNORM, 0 });
			}
			const bool reading = peer.queued < kHighWater;  // a socket we won't read must not wake us
			for (const auto& [sid, stream] : peer.streams) {
				SHORT events = 0;
				if (stream.connecting || stream.outSent < stream.out.size()) {
					events |= POLLWRNORM;
				}
				if (!stream.connecting && !stream.closing && reading) {
					events |= POLLRDNORM;
				}
				if (events) {
					a_fds.push_back({ stream.socket, events, 0 });
				}
			}
		}
	}

	void Tunnel::Stop(Clock::time_point a_now)
	{
		for (auto& [id, peer] : peers_) {
			if (peer.hello) {
				Queue(id, peer, EncodeBye(), a_now);
			}
		}
		stopping_ = true;
	}

	void Tunnel::OnMessage(const Message& a_message, Clock::time_point a_now)
	{
		const auto frame = Decode(a_message.data);
		if (!frame) {
			return;  // damaged, or not ours
		}
		if (frame->type == Type::kHello) {
			OnHello(a_message.from, *frame, a_now);
			return;
		}
		const auto it = peers_.find(a_message.from);
		if (it == peers_.end() || !it->second.hello) {
			return;  // nothing counts before hello
		}
		Peer& peer = it->second;
		peer.heard = a_now;
		switch (frame->type) {
		case Type::kBye:
			Drop(a_message.from, "bye");
			break;
		case Type::kOpen:
			OnOpen(a_message.from, peer, frame->stream, frame->service, a_now);
			break;
		case Type::kData:
			OnData(a_message.from, peer, frame->stream, frame->bytes, a_now);
			break;
		case Type::kClose:
			OnClose(peer, frame->stream, frame->reason, a_now);
			break;
		case Type::kDatagram:
			OnDatagram(peer, frame->service, frame->bytes);
			break;
		default:
			break;  // keep-alive: being heard is all it says
		}
	}

	void Tunnel::OnHello(PeerId a_from, const Frame& a_frame, Clock::time_point a_now)
	{
		if (options_.host) {
			if (a_frame.role != kRoleGuest) {
				return;
			}
			auto [it, fresh] = peers_.try_emplace(a_from);
			Peer&      peer = it->second;
			const bool again = !fresh && peer.hello;
			Reset(peer);  // a guest that says hello again started over: its old streams are gone
			peer.heard = a_now;
			peer.name = a_frame.name;
			peer.hello = a_frame.version == kProtocol;
			Queue(a_from, peer, EncodeHello(kRoleHost, options_.name), a_now);  // tells our version either way
			if (!peer.hello) {
				Event("peer").Str("state", "refused").Str("peer", std::to_string(a_from)).Str("name", NameOf(a_from, peer)).Num("version", a_frame.version).Send();
			} else if (!again) {
				Event("peer").Str("state", "joined").Str("peer", std::to_string(a_from)).Str("name", NameOf(a_from, peer)).Send();
			}
			return;
		}
		if (a_from != host_ || a_frame.role != kRoleHost) {
			return;
		}
		Peer& peer = peers_[host_];
		peer.heard = a_now;
		if (peer.hello || finished_) {
			return;
		}
		peer.name = a_frame.name;
		if (a_frame.version != kProtocol) {
			Fail("version", "У хоста другая версия SkyCraft Link (" + std::to_string(a_frame.version) + ", у вас " + std::to_string(kProtocol) +
			                    "). Обновите мод у обоих.");
			return;
		}
		peer.hello = true;
		Event("connected").Str("host", NameOf(host_, peer)).Send();
		OpenListeners();
	}

	void Tunnel::OnOpen(PeerId a_id, Peer& a_peer, std::uint32_t a_stream, Service a_service, Clock::time_point a_now)
	{
		if (!options_.host || a_peer.streams.contains(a_stream)) {
			return;
		}
		SOCKET socket = INVALID_SOCKET;
		if (a_service == Service::kMinecraft && a_peer.streams.size() < kMaxStreams) {
			socket = net::StartConnect(options_.minecraftPort);
		}
		if (socket == INVALID_SOCKET) {
			Queue(a_id, a_peer, EncodeClose(a_stream, CloseReason::kRefused), a_now);
			return;
		}
		Stream& stream = a_peer.streams[a_stream];
		stream.socket = socket;
		stream.connecting = true;
		stream.opened = a_now;
	}

	void Tunnel::OnData(PeerId a_id, Peer& a_peer, std::uint32_t a_stream, std::span<const std::uint8_t> a_bytes, Clock::time_point a_now)
	{
		const auto it = a_peer.streams.find(a_stream);
		if (it == a_peer.streams.end() || it->second.closing) {
			return;  // closed here already: the other side learns it from our Close
		}
		Stream& stream = it->second;
		bool    failed = stream.out.size() - stream.outSent + a_bytes.size() > kMaxBacklog;
		if (!failed) {
			stream.out.insert(stream.out.end(), a_bytes.begin(), a_bytes.end());
			failed = !stream.connecting && !WriteStream(stream);
		}
		if (failed) {
			Queue(a_id, a_peer, EncodeClose(a_stream, CloseReason::kClosed), a_now);
			CloseStream(stream);
			a_peer.streams.erase(it);
		}
	}

	void Tunnel::OnClose(Peer& a_peer, std::uint32_t a_stream, CloseReason a_reason, Clock::time_point a_now)
	{
		const auto it = a_peer.streams.find(a_stream);
		if (it == a_peer.streams.end()) {
			return;
		}
		if (!options_.host && a_reason == CloseReason::kRefused) {
			Event("refused").Str("service", "minecraft").Str("text", "Хост ещё не открыл мир Minecraft для сети.").Send();
		}
		it->second.closing = true;  // PumpStreams writes what is left, then closes it
		it->second.closingSince = a_now;
	}

	void Tunnel::OnDatagram(Peer& a_peer, Service a_service, std::span<const std::uint8_t> a_bytes)
	{
		if (a_service != Service::kTogether || !options_.together) {
			return;
		}
		if (options_.host) {
			if (a_peer.udp == INVALID_SOCKET) {
				// A socket of its own per guest: the server tells its clients apart by their ports.
				a_peer.udp = net::Udp(0, options_.togetherPort, nullptr);
			}
			if (a_peer.udp != INVALID_SOCKET) {
				::send(a_peer.udp, reinterpret_cast<const char*>(a_bytes.data()), static_cast<int>(a_bytes.size()), 0);
			}
		} else if (together_ != INVALID_SOCKET && togetherClientKnown_) {
			::sendto(together_, reinterpret_cast<const char*>(a_bytes.data()), static_cast<int>(a_bytes.size()), 0,
				reinterpret_cast<const sockaddr*>(&togetherClient_), sizeof(togetherClient_));
		}
	}

	void Tunnel::PumpStreams(PeerId a_id, Peer& a_peer, Clock::time_point a_now)
	{
		std::uint8_t buffer[kChunk];
		for (auto it = a_peer.streams.begin(); it != a_peer.streams.end();) {
			const std::uint32_t sid = it->first;
			Stream&             stream = it->second;
			bool                drop = false;
			bool                tell = false;  // the other side still thinks it is open
			if (stream.connecting) {
				WSAPOLLFD  fd{ stream.socket, POLLWRNORM, 0 };
				const bool ready = ::WSAPoll(&fd, 1, 0) > 0;
				// Older Windows 10 never reports a refused connect through WSAPoll: the socket's error does.
				if (net::PendingError(stream.socket) != 0 || (ready && (fd.revents & (POLLERR | POLLHUP))) || a_now - stream.opened > kConnectWait) {
					Queue(a_id, a_peer, EncodeClose(sid, CloseReason::kRefused), a_now);
					drop = true;
				} else if (ready) {
					stream.connecting = false;
				}
			}
			if (!drop && !stream.connecting) {
				if (!WriteStream(stream)) {
					drop = true;
					tell = !stream.closing;
				} else if (stream.closing) {
					drop = stream.outSent == stream.out.size() || a_now - stream.closingSince > kClosingWait;
				} else {
					for (int i = 0; i < kReadBudget && a_peer.queued < kHighWater; ++i) {
						const int got = ::recv(stream.socket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
						if (got > 0) {
							Queue(a_id, a_peer, EncodeData(sid, { buffer, static_cast<std::size_t>(got) }), a_now);
							continue;
						}
						if (got == 0 || ::WSAGetLastError() != WSAEWOULDBLOCK) {
							drop = true;
							tell = true;
						}
						break;
					}
				}
			}
			if (drop) {
				if (tell) {
					Queue(a_id, a_peer, EncodeClose(sid, CloseReason::kClosed), a_now);
				}
				CloseStream(stream);
				it = a_peer.streams.erase(it);
			} else {
				++it;
			}
		}
	}

	void Tunnel::PumpDatagrams()
	{
		std::uint8_t buffer[kMaxDatagram];
		auto         forward = [&](PeerId a_to, int a_got) {
			if (a_got > 0) {
				transport_.Send(a_to, kChannelDatagrams, EncodeDatagram(Service::kTogether, { buffer, static_cast<std::size_t>(a_got) }), false);
			}
		};
		if (options_.host) {
			for (auto& [id, peer] : peers_) {
				for (int i = 0; peer.udp != INVALID_SOCKET && i < kDatagramBudget; ++i) {
					const int got = ::recv(peer.udp, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
					if (got < 0 && ::WSAGetLastError() != WSAEMSGSIZE) {
						break;
					}
					forward(id, got);  // an oversized one (WSAEMSGSIZE) is dropped
				}
			}
			return;
		}
		const auto host = peers_.find(host_);
		const bool ready = host != peers_.end() && host->second.hello;
		for (int i = 0; together_ != INVALID_SOCKET && i < kDatagramBudget; ++i) {
			sockaddr_in from{};
			int         length = sizeof(from);
			const int   got = ::recvfrom(together_, reinterpret_cast<char*>(buffer), sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&from), &length);
			if (got < 0 && ::WSAGetLastError() != WSAEMSGSIZE) {
				break;
			}
			if (got > 0 && ready) {
				togetherClient_ = from;  // where the answers go: our player's Skyrim Together
				togetherClientKnown_ = true;
				forward(host_, got);
			}
		}
	}

	void Tunnel::AcceptLocal(Clock::time_point a_now)
	{
		const auto it = peers_.find(host_);
		if (minecraftListen_ == INVALID_SOCKET || it == peers_.end() || !it->second.hello) {
			return;
		}
		Peer& peer = it->second;
		for (;;) {
			const SOCKET socket = ::accept(minecraftListen_, nullptr, nullptr);
			if (socket == INVALID_SOCKET) {
				return;
			}
			u_long on = 1;
			::ioctlsocket(socket, FIONBIO, &on);
			NoDelay(socket);
			const std::uint32_t id = nextStream_++;
			peer.streams[id].socket = socket;
			Queue(host_, peer, EncodeOpen(id, Service::kMinecraft), a_now);
		}
	}

	void Tunnel::OpenListeners()
	{
		minecraftListen_ = net::Listen(options_.minecraftPort, &minecraftBound_);
		if (minecraftListen_ == INVALID_SOCKET && options_.minecraftPort != 0) {
			minecraftListen_ = net::Listen(0, &minecraftBound_);  // the usual port is taken here: any free one
		}
		if (minecraftListen_ == INVALID_SOCKET) {
			Fail("listen", "Не удалось открыть локальный порт для Minecraft: " + net::ErrorText(::WSAGetLastError()));
			return;
		}
		Event("listening").Str("service", "minecraft").Num("port", minecraftBound_).Send();
		if (!options_.together) {
			return;
		}
		together_ = net::Udp(options_.togetherPort, 0, &togetherBound_);
		if (together_ == INVALID_SOCKET && options_.togetherPort != 0) {
			together_ = net::Udp(0, 0, &togetherBound_);
		}
		if (together_ != INVALID_SOCKET) {
			Event("listening").Str("service", "together").Num("port", togetherBound_).Send();
		} else {
			Event("warning").Str("code", "together_port").Str("text", "Не удалось открыть порт для Skyrim Together; Minecraft работает.").Send();
		}
	}

	void Tunnel::Queue(PeerId a_id, Peer& a_peer, std::vector<std::uint8_t> a_frame, Clock::time_point a_now)
	{
		if (a_peer.reliable.empty() && transport_.Send(a_id, kChannelStreams, a_frame, true)) {
			a_peer.sent = a_now;
			return;
		}
		a_peer.queued += a_frame.size();
		a_peer.reliable.push_back(std::move(a_frame));
	}

	void Tunnel::Flush(PeerId a_id, Peer& a_peer, Clock::time_point a_now)
	{
		while (!a_peer.reliable.empty() && transport_.Send(a_id, kChannelStreams, a_peer.reliable.front(), true)) {
			a_peer.queued -= a_peer.reliable.front().size();
			a_peer.reliable.pop_front();
			a_peer.sent = a_now;
		}
	}

	void Tunnel::Drop(PeerId a_id, std::string_view a_reason)
	{
		const auto it = peers_.find(a_id);
		if (it == peers_.end()) {
			return;
		}
		const bool        joined = it->second.hello;
		const std::string name = NameOf(a_id, it->second);
		Reset(it->second);
		peers_.erase(it);
		transport_.Forget(a_id);
		if (options_.host) {
			if (joined) {
				Event("peer").Str("state", "left").Str("peer", std::to_string(a_id)).Str("name", name).Str("reason", a_reason).Send();
			}
		} else if (a_id == host_) {
			if (a_reason == "bye") {
				Fail("closed", "Хост закрыл мир.");
			} else {
				Fail("lost", a_reason == "silent" ? "Хост не отвечает 20 секунд." : "Связь с хостом потеряна.");
			}
		}
	}

	void Tunnel::Fail(std::string_view a_code, const std::string& a_text)
	{
		if (finished_) {
			return;
		}
		finished_ = true;
		failure_ = a_code;
		Event("lost").Str("code", a_code).Str("text", a_text).Send();
	}

	std::string Tunnel::NameOf(PeerId a_id, const Peer& a_peer)
	{
		std::string name = transport_.Name(a_id);
		return name.empty() ? a_peer.name : name;
	}

	void Tunnel::Reset(Peer& a_peer)
	{
		for (auto& [id, stream] : a_peer.streams) {
			CloseStream(stream);
		}
		a_peer.streams.clear();
		net::Close(a_peer.udp);
		a_peer.reliable.clear();
		a_peer.queued = 0;
		a_peer.hello = false;
	}

	void Tunnel::CloseStream(Stream& a_stream)
	{
		if (a_stream.socket == INVALID_SOCKET) {
			return;
		}
		if (!a_stream.connecting) {
			::shutdown(a_stream.socket, SD_SEND);
			char sink[4096];
			while (::recv(a_stream.socket, sink, sizeof(sink), 0) > 0) {}  // unread data would turn the close into a reset
		}
		net::Close(a_stream.socket);
	}

	bool Tunnel::WriteStream(Stream& a_stream)
	{
		while (a_stream.outSent < a_stream.out.size()) {
			const std::size_t left = std::min<std::size_t>(a_stream.out.size() - a_stream.outSent, 1u << 20);
			const int         sent = ::send(a_stream.socket, reinterpret_cast<const char*>(a_stream.out.data() + a_stream.outSent), static_cast<int>(left), 0);
			if (sent <= 0) {
				if (::WSAGetLastError() != WSAEWOULDBLOCK) {
					return false;
				}
				if (a_stream.outSent >= (1u << 20)) {  // keep the buffer from only ever growing
					a_stream.out.erase(a_stream.out.begin(), a_stream.out.begin() + static_cast<std::ptrdiff_t>(a_stream.outSent));
					a_stream.outSent = 0;
				}
				return true;
			}
			a_stream.outSent += static_cast<std::size_t>(sent);
		}
		a_stream.out.clear();
		a_stream.outSent = 0;
		return true;
	}
}
