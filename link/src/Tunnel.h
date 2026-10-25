#pragma once
#include "Frames.h"
#include "Transport.h"

#include <chrono>
#include <deque>
#include <map>
#include <string>
#include <string_view>
#include <vector>

namespace link
{
	struct TunnelOptions
	{
		bool        host = false;
		std::string name;  // told to the other side
		// Host: where its Minecraft (LAN) server and Skyrim Together server listen.
		// Guest: the local ports its own games connect to (if taken: any free port).
		std::uint16_t minecraftPort = 25565;
		std::uint16_t togetherPort = 10578;
		bool          together = true;  // also carry Skyrim Together's UDP
	};

	// Carries the games' traffic between a guest's PC and the host's: every TCP connection to the
	// guest's local Minecraft port becomes a stream to the host's Minecraft server, and Skyrim
	// Together's UDP datagrams travel unreliably the same way. Single-threaded: Pump() it in a loop.
	class Tunnel
	{
	public:
		using Clock = std::chrono::steady_clock;

		static constexpr auto        kKeepAlive = std::chrono::seconds(2);
		static constexpr auto        kSilence = std::chrono::seconds(20);  // a peer this quiet is gone
		static constexpr auto        kHelloWait = std::chrono::seconds(20);
		static constexpr std::size_t kHighWater = 1u << 20;  // past this backlog, local sockets wait
		static constexpr std::size_t kMaxStreams = 32;       // per guest

		Tunnel(Transport& a_transport, TunnelOptions a_options);
		~Tunnel();
		Tunnel(const Tunnel&) = delete;
		Tunnel& operator=(const Tunnel&) = delete;

		void Pump(Clock::time_point a_now);
		void AddSockets(std::vector<WSAPOLLFD>& a_fds) const;
		// Says goodbye to every peer; afterwards Pump() only delivers what is still queued.
		void Stop(Clock::time_point a_now);

		bool               Finished() const { return finished_; }  // guest: the host is gone
		const std::string& Failure() const { return failure_; }
		std::size_t        Peers() const;  // that said hello: host: guests; guest: the host
		std::size_t        Streams() const;
		std::uint16_t      MinecraftPort() const { return minecraftBound_; }  // guest: where Minecraft connects
		std::uint16_t      TogetherPort() const { return togetherBound_; }    // guest: where Skyrim Together connects

	private:
		struct Stream
		{
			SOCKET                    socket = INVALID_SOCKET;
			bool                      connecting = false;  // host: still connecting to its Minecraft server
			bool                      closing = false;     // the other side closed it: write what's left, then close
			Clock::time_point         opened{};
			Clock::time_point         closingSince{};
			std::vector<std::uint8_t> out;  // for the local socket
			std::size_t               outSent = 0;
		};

		struct Peer
		{
			std::string                           name;
			bool                                  hello = false;
			Clock::time_point                     heard{};
			Clock::time_point                     sent{};
			std::deque<std::vector<std::uint8_t>> reliable;  // waiting for room in the transport, in order
			std::size_t                           queued = 0;
			std::map<std::uint32_t, Stream>       streams;
			SOCKET                                udp = INVALID_SOCKET;  // host: this guest's own socket to Skyrim Together
		};

		void        OnMessage(const Message& a_message, Clock::time_point a_now);
		void        OnHello(PeerId a_from, const Frame& a_frame, Clock::time_point a_now);
		void        OnOpen(PeerId a_id, Peer& a_peer, std::uint32_t a_stream, Service a_service, Clock::time_point a_now);
		void        OnData(PeerId a_id, Peer& a_peer, std::uint32_t a_stream, std::span<const std::uint8_t> a_bytes, Clock::time_point a_now);
		void        OnClose(Peer& a_peer, std::uint32_t a_stream, CloseReason a_reason, Clock::time_point a_now);
		void        OnDatagram(Peer& a_peer, Service a_service, std::span<const std::uint8_t> a_bytes);
		void        PumpStreams(PeerId a_id, Peer& a_peer, Clock::time_point a_now);
		void        PumpDatagrams();
		void        AcceptLocal(Clock::time_point a_now);
		void        OpenListeners();
		void        Queue(PeerId a_id, Peer& a_peer, std::vector<std::uint8_t> a_frame, Clock::time_point a_now);
		void        Flush(PeerId a_id, Peer& a_peer, Clock::time_point a_now);
		void        Drop(PeerId a_id, std::string_view a_reason);
		void        Fail(std::string_view a_code, const std::string& a_text);
		std::string NameOf(PeerId a_id, const Peer& a_peer);
		static void Reset(Peer& a_peer);
		static void CloseStream(Stream& a_stream);
		static bool WriteStream(Stream& a_stream);  // false: the socket failed

		Transport&             transport_;
		TunnelOptions          options_;
		std::map<PeerId, Peer> peers_;
		std::vector<Message>   inbox_;
		std::vector<PeerId>    lost_;
		bool                   stopping_ = false;
		bool                   finished_ = false;
		std::string            failure_;
		// Guest side
		PeerId            host_ = 0;
		Clock::time_point helloAt_{};
		SOCKET            minecraftListen_ = INVALID_SOCKET;
		SOCKET            together_ = INVALID_SOCKET;
		sockaddr_in       togetherClient_{};
		bool              togetherClientKnown_ = false;
		std::uint32_t     nextStream_ = 1;
		std::uint16_t     minecraftBound_ = 0;
		std::uint16_t     togetherBound_ = 0;
	};
}
