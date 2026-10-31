#pragma once
#include <winsock2.h>

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace link
{
	using PeerId = std::uint64_t;

	struct Message
	{
		PeerId                    from = 0;
		int                       channel = 0;
		std::vector<std::uint8_t> data;
	};

	// How two SkyCraft Link programs reach each other: through Steam's relays between friends, or
	// over a direct TCP link on this PC (automated tests).
	class Transport
	{
	public:
		virtual ~Transport() = default;

		virtual PeerId      Self() const = 0;
		// Guest: the host, once the transport reached it (0 until then). Host: 0.
		virtual PeerId      HostPeer() { return 0; }
		virtual std::string Name(PeerId a_peer) = 0;
		// Reliable: ordered and resent until delivered; otherwise it may be lost. False: no room
		// right now, the caller keeps the data and tries again.
		virtual bool Send(PeerId a_peer, int a_channel, std::span<const std::uint8_t> a_data, bool a_reliable) = 0;
		// Runs the transport's own work (callbacks, sockets) and hands over what arrived.
		virtual void Pump(std::vector<Message>& a_out) = 0;
		// Sockets the event loop should also wait on (none for Steam).
		virtual void AddSockets(std::vector<WSAPOLLFD>&) {}
		// Peers the transport itself lost (a dropped link).
		virtual void TakeLost(std::vector<PeerId>&) {}
		// Done with this peer: end its session.
		virtual void Forget(PeerId) {}
	};
}
