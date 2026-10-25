// SkyCraft Link checks without Steam: frames, and whole tunnels (host and guests in this process)
// over the loopback transport, against local echo servers standing in for Minecraft and Skyrim
// Together. Exit code: the number of failed checks.
#include "Frames.h"
#include "LoopbackTransport.h"
#include "Net.h"
#include "Status.h"
#include "Tunnel.h"

#include <algorithm>
#include <cstdio>
#include <functional>
#include <memory>
#include <random>
#include <set>
#include <string>
#include <vector>

using namespace link;
using namespace std::chrono_literals;
using Clock = std::chrono::steady_clock;

namespace
{
	int                      g_failures = 0;
	bool                     g_verbose = false;
	std::vector<std::string> g_events;

#define CHECK(x)                                                              \
	do {                                                                      \
		if (!(x)) {                                                           \
			std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #x);          \
			++g_failures;                                                     \
		}                                                                     \
	} while (0)

	bool Saw(std::string_view a_text)
	{
		return std::ranges::any_of(g_events, [&](const std::string& a_line) { return a_line.find(a_text) != std::string::npos; });
	}

	std::vector<std::uint8_t> Bytes(std::size_t a_size, std::uint32_t a_seed)
	{
		std::mt19937              random(a_seed);
		std::vector<std::uint8_t> out(a_size);
		for (auto& b : out) {
			b = static_cast<std::uint8_t>(random());
		}
		return out;
	}

	void NonBlocking(SOCKET a_socket)
	{
		u_long on = 1;
		::ioctlsocket(a_socket, FIONBIO, &on);
	}

	// Stand-in for the host's Minecraft server: echoes every byte back.
	struct EchoTcp
	{
		struct Connection
		{
			SOCKET                    socket;
			std::vector<std::uint8_t> pending;
		};

		SOCKET                  listening = INVALID_SOCKET;
		std::uint16_t           port = 0;
		std::vector<Connection> connections;
		int                     accepted = 0;
		int                     closed = 0;

		EchoTcp() { listening = net::Listen(0, &port); }
		~EchoTcp()
		{
			for (auto& c : connections) {
				net::Close(c.socket);
			}
			net::Close(listening);
		}

		void Pump()
		{
			for (SOCKET s; (s = ::accept(listening, nullptr, nullptr)) != INVALID_SOCKET;) {
				NonBlocking(s);
				connections.push_back({ s, {} });
				++accepted;
			}
			for (auto it = connections.begin(); it != connections.end();) {
				std::uint8_t buffer[64 * 1024];
				bool         gone = false;
				for (;;) {
					const int got = ::recv(it->socket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
					if (got > 0) {
						it->pending.insert(it->pending.end(), buffer, buffer + got);
						continue;
					}
					gone = got == 0 || ::WSAGetLastError() != WSAEWOULDBLOCK;
					break;
				}
				while (!it->pending.empty()) {
					const int sent = ::send(it->socket, reinterpret_cast<const char*>(it->pending.data()), static_cast<int>(std::min<std::size_t>(it->pending.size(), 1 << 20)), 0);
					if (sent <= 0) {
						break;
					}
					it->pending.erase(it->pending.begin(), it->pending.begin() + sent);
				}
				if (gone) {
					net::Close(it->socket);
					it = connections.erase(it);
					++closed;
				} else {
					++it;
				}
			}
		}
	};

	// Stand-in for the host's Skyrim Together server: echoes datagrams, notes who sent them.
	struct EchoUdp
	{
		SOCKET                  socket = INVALID_SOCKET;
		std::uint16_t           port = 0;
		int                     echoed = 0;
		std::set<std::uint16_t> senders;

		EchoUdp() { socket = net::Udp(0, 0, &port); }
		~EchoUdp() { net::Close(socket); }

		void Pump()
		{
			std::uint8_t buffer[64 * 1024];
			for (;;) {
				sockaddr_in from{};
				int         length = sizeof(from);
				const int   got = ::recvfrom(socket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&from), &length);
				if (got <= 0) {
					break;
				}
				::sendto(socket, reinterpret_cast<const char*>(buffer), got, 0, reinterpret_cast<const sockaddr*>(&from), length);
				senders.insert(ntohs(from.sin_port));
				++echoed;
			}
		}
	};

	// Says "later" to every third reliable message: the tunnel must keep order through congestion.
	class Choking final : public Transport
	{
	public:
		explicit Choking(std::unique_ptr<Transport> a_inner) :
			inner_(std::move(a_inner))
		{}

		PeerId      Self() const override { return inner_->Self(); }
		PeerId      HostPeer() override { return inner_->HostPeer(); }
		std::string Name(PeerId a_peer) override { return inner_->Name(a_peer); }
		bool        Send(PeerId a_peer, int a_channel, std::span<const std::uint8_t> a_data, bool a_reliable) override
		{
			if (a_reliable && ++count_ % 3 == 0) {
				return false;
			}
			return inner_->Send(a_peer, a_channel, a_data, a_reliable);
		}
		void Pump(std::vector<Message>& a_out) override { inner_->Pump(a_out); }
		void AddSockets(std::vector<WSAPOLLFD>& a_fds) override { inner_->AddSockets(a_fds); }
		void TakeLost(std::vector<PeerId>& a_out) override { inner_->TakeLost(a_out); }
		void Forget(PeerId a_peer) override { inner_->Forget(a_peer); }

	private:
		std::unique_ptr<Transport> inner_;
		std::uint64_t              count_ = 0;
	};

	std::unique_ptr<Transport> Wrap(std::unique_ptr<LoopbackTransport> a_link, bool a_choke)
	{
		if (a_choke) {
			return std::make_unique<Choking>(std::move(a_link));
		}
		return a_link;
	}

	struct Side
	{
		std::unique_ptr<Transport> link;
		std::unique_ptr<Tunnel>    tunnel;
	};

	struct World
	{
		EchoTcp           minecraft;
		EchoUdp           together;
		LoopbackTransport* hostLink = nullptr;
		Side              host;
		std::vector<Side> guests;

		explicit World(bool a_choke = false, std::uint16_t a_minecraftPort = 0)
		{
			std::string error;
			auto        link = LoopbackTransport::Host(0, error);
			hostLink = link.get();
			host.link = Wrap(std::move(link), a_choke);
			TunnelOptions options;
			options.host = true;
			options.name = "host";
			options.minecraftPort = a_minecraftPort ? a_minecraftPort : minecraft.port;
			options.togetherPort = together.port;
			host.tunnel = std::make_unique<Tunnel>(*host.link, options);
		}

		Tunnel& AddGuest(bool a_choke = false)
		{
			Side& side = guests.emplace_back();
			side.link = Wrap(LoopbackTransport::Guest(hostLink->Port()), a_choke);
			TunnelOptions options;
			options.name = "guest";
			options.minecraftPort = 0;  // any free port: these tests run beside real games
			options.togetherPort = 0;
			side.tunnel = std::make_unique<Tunnel>(*side.link, options);
			return *side.tunnel;
		}

		void Pump()
		{
			const auto now = Clock::now();
			if (host.tunnel) {
				host.tunnel->Pump(now);
			}
			for (auto& guest : guests) {
				if (guest.tunnel) {
					guest.tunnel->Pump(now);
				}
			}
			minecraft.Pump();
			together.Pump();
		}

		bool Until(const std::function<bool()>& a_done, std::chrono::milliseconds a_limit = 5s)
		{
			for (const auto until = Clock::now() + a_limit; Clock::now() < until;) {
				Pump();
				if (a_done()) {
					return true;
				}
				::Sleep(1);
			}
			return false;
		}

		bool Connected(const Tunnel& a_guest) { return Until([&] { return a_guest.MinecraftPort() != 0; }); }
	};

	SOCKET Dial(std::uint16_t a_port)
	{
		SOCKET     s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
		const auto addr = net::Loopback(a_port);
		if (::connect(s, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0) {
			net::Close(s);
			return INVALID_SOCKET;
		}
		NonBlocking(s);
		return s;
	}

	// One client of the guest's local Minecraft port: sends its bytes, expects them all back.
	struct Client
	{
		SOCKET                    socket = INVALID_SOCKET;
		std::vector<std::uint8_t> data;
		std::vector<std::uint8_t> back;
		std::size_t               sent = 0;
		bool                      eof = false;

		Client(std::uint16_t a_port, std::size_t a_size, std::uint32_t a_seed) :
			socket(Dial(a_port)), data(Bytes(a_size, a_seed))
		{}
		~Client() { net::Close(socket); }

		void Pump()
		{
			if (socket == INVALID_SOCKET) {
				return;
			}
			while (sent < data.size()) {
				const int n = ::send(socket, reinterpret_cast<const char*>(data.data() + sent), static_cast<int>(std::min<std::size_t>(data.size() - sent, 64 * 1024)), 0);
				if (n <= 0) {
					break;
				}
				sent += static_cast<std::size_t>(n);
			}
			std::uint8_t buffer[64 * 1024];
			for (;;) {
				const int n = ::recv(socket, reinterpret_cast<char*>(buffer), sizeof(buffer), 0);
				if (n > 0) {
					back.insert(back.end(), buffer, buffer + n);
					continue;
				}
				eof = eof || n == 0 || ::WSAGetLastError() != WSAEWOULDBLOCK;
				break;
			}
		}

		bool Done() const { return back.size() >= data.size() || eof; }
		bool Echoed() const { return back == data; }
	};

	void TestFrames()
	{
		const std::vector<std::uint8_t> payload{ 1, 2, 3, 250 };
		{
			auto f = Decode(EncodeHello(2, "Довакин"));
			CHECK(f && f->type == Type::kHello && f->version == kProtocol && f->role == 2 && f->name == "Довакин");
		}
		{
			auto f = Decode(EncodeOpen(77, Service::kMinecraft));
			CHECK(f && f->type == Type::kOpen && f->stream == 77 && f->service == Service::kMinecraft);
		}
		{
			const auto m = EncodeData(5, payload);
			auto       f = Decode(m);
			CHECK(f && f->stream == 5 && std::ranges::equal(f->bytes, payload));
		}
		{
			auto f = Decode(EncodeClose(9, CloseReason::kRefused));
			CHECK(f && f->type == Type::kClose && f->stream == 9 && f->reason == CloseReason::kRefused);
		}
		{
			const auto m = EncodeDatagram(Service::kTogether, payload);
			auto       f = Decode(m);
			CHECK(f && f->service == Service::kTogether && std::ranges::equal(f->bytes, payload));
		}
		CHECK(Decode(EncodeKeepAlive()) && Decode(EncodeBye()));
		// Nothing malformed gets through.
		CHECK(!Decode(std::vector<std::uint8_t>{}));
		CHECK(!Decode(std::vector<std::uint8_t>{ 99 }));
		CHECK(!Decode(std::vector<std::uint8_t>{ 1, 1, 0, 3, 0 }));      // hello: no such role
		CHECK(!Decode(std::vector<std::uint8_t>{ 1, 1, 0, 2, 5, 'a' }));  // hello: name shorter than said
		CHECK(!Decode(std::vector<std::uint8_t>{ 2, 0 }));               // keep-alive with a tail
		CHECK(!Decode(std::vector<std::uint8_t>{ 4, 1, 0, 0, 0, 9 }));   // open: no such service
		CHECK(!Decode(std::vector<std::uint8_t>{ 5, 1, 0, 0 }));         // data: no stream id
		CHECK(!Decode(EncodeData(1, std::vector<std::uint8_t>(kChunk + 1))));
		CHECK(!Decode(std::vector<std::uint8_t>{ 6, 1, 0, 0, 0, 3 }));  // close: no such reason
		CHECK(!Decode(std::vector<std::uint8_t>{ 7, 2 }));              // datagram: empty
		CHECK(!Decode(EncodeDatagram(Service::kTogether, std::vector<std::uint8_t>(kMaxDatagram + 1))));
		CHECK(Decode(EncodeHello(1, std::string(200, 'x'))).value_or(Frame{}).name.size() == 64);  // long names are cut
	}

	void TestStreams(bool a_choke)
	{
		World   world(a_choke);
		Tunnel& guest = world.AddGuest(a_choke);
		CHECK(world.Connected(guest));
		CHECK(world.host.tunnel->Peers() == 1);
		CHECK(Saw("\"connected\"") && Saw("\"joined\""));
		{
			// One big transfer (a world loading) and many small streams at once.
			std::vector<std::unique_ptr<Client>> clients;
			clients.push_back(std::make_unique<Client>(guest.MinecraftPort(), a_choke ? (4u << 20) : (16u << 20), 1));
			for (std::uint32_t i = 0; i < 12; ++i) {
				clients.push_back(std::make_unique<Client>(guest.MinecraftPort(), 1000 + i * 7919, 100 + i));
			}
			const bool done = world.Until(
				[&] {
					for (auto& c : clients) {
						c->Pump();
					}
					return std::ranges::all_of(clients, [](const auto& a_c) { return a_c->Done(); });
				},
				60s);
			CHECK(done);
			CHECK(std::ranges::all_of(clients, [](const auto& a_c) { return a_c->Echoed(); }));
			CHECK(world.minecraft.accepted == 13);
		}
		// Clients gone: every stream closes on both sides.
		CHECK(world.Until([&] { return world.minecraft.closed == 13 && guest.Streams() == 0 && world.host.tunnel->Streams() == 0; }));
	}

	void TestDatagrams()
	{
		World   world;
		Tunnel& first = world.AddGuest();
		Tunnel& second = world.AddGuest();
		CHECK(world.Connected(first) && world.Connected(second));
		CHECK(world.Until([&] { return world.host.tunnel->Peers() == 2; }));
		for (Tunnel* guest : { &first, &second }) {
			SOCKET     udp = net::Udp(0, guest->TogetherPort(), nullptr);
			std::set<std::vector<std::uint8_t>> expected, received;
			for (std::uint32_t i = 0; i < 200; ++i) {
				auto datagram = Bytes(1 + (i * 37) % 1400, 1000 + i);
				::send(udp, reinterpret_cast<const char*>(datagram.data()), static_cast<int>(datagram.size()), 0);
				expected.insert(std::move(datagram));
				if (i % 20 == 0) {
					world.Pump();
				}
			}
			world.Until([&] {
				std::uint8_t buffer[2048];
				for (int n; (n = ::recv(udp, reinterpret_cast<char*>(buffer), sizeof(buffer), 0)) > 0;) {
					received.emplace(buffer, buffer + n);
				}
				return received.size() == expected.size();
			});
			CHECK(received == expected);  // the loopback link loses nothing
			net::Close(udp);
		}
		CHECK(world.together.senders.size() == 2);  // each guest has its own port at the server
	}

	void TestRefused()
	{
		std::uint16_t closed = 0;
		SOCKET        probe = net::Listen(0, &closed);
		net::Close(probe);  // nothing listens there now: the host's Minecraft isn't open
		World   world(false, closed);
		Tunnel& guest = world.AddGuest();
		CHECK(world.Connected(guest));
		Client client(guest.MinecraftPort(), 100, 7);
		CHECK(world.Until([&] { client.Pump(); return client.eof; }, 10s));  // Windows retries a refused connect for ~2 s
		CHECK(Saw("\"refused\""));
		CHECK(world.Until([&] { return guest.Streams() == 0 && world.host.tunnel->Streams() == 0; }));
	}

	void TestLoss()
	{
		{
			World   world;
			Tunnel& guest = world.AddGuest();
			CHECK(world.Connected(guest));
			world.host.tunnel.reset();  // the host's program ends without a word
			world.host.link.reset();
			CHECK(world.Until([&] { return guest.Finished(); }));
			CHECK(guest.Failure() == "lost");
		}
		{
			World   world;
			Tunnel& guest = world.AddGuest();
			CHECK(world.Connected(guest));
			CHECK(world.Until([&] { return world.host.tunnel->Peers() == 1; }));
			world.guests.front().tunnel->Stop(Clock::now());  // the guest leaves properly
			CHECK(world.Until([&] { return world.host.tunnel->Peers() == 0; }));
			CHECK(Saw("\"reason\":\"bye\""));
			world.guests.clear();
		}
		{
			World   world;
			Tunnel& guest = world.AddGuest();
			CHECK(world.Connected(guest));
			world.host.tunnel->Stop(Clock::now());  // the host closes its world
			CHECK(world.Until([&] { return guest.Finished(); }));
			CHECK(guest.Failure() == "closed");
		}
	}

	void TestVersions()
	{
		// A guest of another version gets the host's hello (so it can say why) and nothing else.
		{
			World world;
			auto  fake = LoopbackTransport::Guest(world.hostLink->Port());
			CHECK(world.Until([&] { std::vector<Message> m; fake->Pump(m); return fake->HostPeer() != 0; }));
			const std::vector<std::uint8_t> hello{ 1, 99, 0, 2, 0 };
			fake->Send(LoopbackTransport::kHost, kChannelStreams, hello, true);
			const std::vector<std::uint8_t> open = EncodeOpen(1, Service::kMinecraft);
			fake->Send(LoopbackTransport::kHost, kChannelStreams, open, true);
			bool answered = false;
			world.Until([&] {
				std::vector<Message> messages;
				fake->Pump(messages);
				for (const auto& m : messages) {
					const auto f = Decode(m.data);
					answered = answered || (f && f->type == Type::kHello && f->version == kProtocol);
				}
				return answered;
			});
			CHECK(answered);
			world.Until([] { return false; }, 200ms);
			CHECK(world.host.tunnel->Peers() == 0 && world.minecraft.accepted == 0);
			CHECK(Saw("\"refused\",\"peer\""));
		}
		// A host of another version: the guest says so and stops.
		{
			std::string error;
			auto        fakeHost = LoopbackTransport::Host(0, error);
			auto        link = LoopbackTransport::Guest(fakeHost->Port());
			Tunnel      guest(*link, TunnelOptions{});
			bool        replied = false;
			for (const auto until = Clock::now() + 5s; Clock::now() < until && !guest.Finished(); ::Sleep(1)) {
				guest.Pump(Clock::now());
				std::vector<Message> messages;
				fakeHost->Pump(messages);
				for (const auto& m : messages) {
					const auto f = Decode(m.data);
					if (f && f->type == Type::kHello && !replied) {
						const std::vector<std::uint8_t> hello{ 1, 99, 0, 1, 0 };
						fakeHost->Send(m.from, kChannelStreams, hello, true);
						replied = true;
					}
				}
			}
			CHECK(guest.Finished() && guest.Failure() == "version");
			CHECK(guest.MinecraftPort() == 0);  // nothing was opened for the games
		}
	}

	void TestGarbage()
	{
		// Junk before and after hello neither breaks the host nor opens anything.
		World world;
		auto  fake = LoopbackTransport::Guest(world.hostLink->Port());
		CHECK(world.Until([&] { std::vector<Message> m; fake->Pump(m); return fake->HostPeer() != 0; }));
		std::mt19937 random(5);
		for (int i = 0; i < 2000; ++i) {
			std::vector<std::uint8_t> junk(random() % 40);
			for (auto& b : junk) {
				b = static_cast<std::uint8_t>(random());
			}
			if (!junk.empty() && junk[0] == 1) {
				junk[0] = 4;  // keep junk from being a valid hello by chance
			}
			fake->Send(LoopbackTransport::kHost, static_cast<int>(random() % 2), junk, true);
		}
		world.Until([] { return false; }, 300ms);
		CHECK(world.host.tunnel->Peers() == 0 && world.minecraft.accepted == 0);
		Tunnel& guest = world.AddGuest();
		CHECK(world.Connected(guest));  // a real guest still gets in
	}
}

int main(int a_argc, char**)
{
	g_verbose = a_argc > 1;
	SetEventSink([](const std::string& a_line) {
		g_events.push_back(a_line);
		if (g_verbose) {
			std::fputs(a_line.c_str(), stdout);
		}
	});
	net::Winsock winsock;
	const std::pair<const char*, std::function<void()>> tests[] = {
		{ "frames", TestFrames },
		{ "streams", [] { TestStreams(false); } },
		{ "streams under congestion", [] { TestStreams(true); } },
		{ "datagrams", TestDatagrams },
		{ "refused", TestRefused },
		{ "loss", TestLoss },
		{ "versions", TestVersions },
		{ "garbage", TestGarbage },
	};
	for (const auto& [name, test] : tests) {
		const int  before = g_failures;
		const auto started = Clock::now();
		g_events.clear();
		test();
		const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - started).count();
		std::printf("%s %s (%lld ms)\n", g_failures == before ? "PASS" : "FAIL", name, static_cast<long long>(ms));
	}
	std::printf(g_failures ? "%d checks failed\n" : "all checks passed\n", g_failures);
	return g_failures;
}
