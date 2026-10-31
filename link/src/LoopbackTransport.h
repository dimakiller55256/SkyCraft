#pragma once
#include "Transport.h"

#include <chrono>
#include <map>
#include <memory>

namespace link
{
	// Two SkyCraft Link programs on one PC over a local TCP connection: the same tunnel as through
	// Steam, for automated tests without two Steam accounts. Messages: u32 length, u8 channel, data.
	class LoopbackTransport final : public Transport
	{
	public:
		static constexpr PeerId kHost = 1;

		// The host side listens on 127.0.0.1:a_port; each guest that connects is a peer.
		static std::unique_ptr<LoopbackTransport> Host(std::uint16_t a_port, std::string& a_error);
		// The guest side connects to the host's port (and keeps trying until it's there).
		static std::unique_ptr<LoopbackTransport> Guest(std::uint16_t a_port);

		~LoopbackTransport() override;

		PeerId        Self() const override { return listening_ != INVALID_SOCKET ? kHost : 2; }
		PeerId        HostPeer() override;
		std::uint16_t Port() const { return port_; }  // host: the port guests connect to
		std::string   Name(PeerId a_peer) override;
		bool        Send(PeerId a_peer, int a_channel, std::span<const std::uint8_t> a_data, bool a_reliable) override;
		void        Pump(std::vector<Message>& a_out) override;
		void        AddSockets(std::vector<WSAPOLLFD>& a_fds) override;
		void        TakeLost(std::vector<PeerId>& a_out) override;
		void        Forget(PeerId a_peer) override;

	private:
		struct Link
		{
			SOCKET                                socket = INVALID_SOCKET;
			bool                                  connecting = false;
			std::chrono::steady_clock::time_point since{};
			std::vector<std::uint8_t>             in;
			std::vector<std::uint8_t>             out;
			std::size_t                           outSent = 0;
		};

		LoopbackTransport() = default;
		void ReadLink(PeerId a_peer, Link& a_link, std::vector<Message>& a_out);
		bool WriteLink(Link& a_link);
		void Drop(PeerId a_peer);

		SOCKET                                listening_ = INVALID_SOCKET;
		std::uint16_t                         port_ = 0;
		std::uint16_t                         hostPort_ = 0;
		std::map<PeerId, Link>                links_;
		std::vector<PeerId>                   lost_;
		PeerId                                nextGuest_ = 0x1000;
		std::chrono::steady_clock::time_point retryAt_{};
	};
}
