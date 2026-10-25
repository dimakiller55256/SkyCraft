#pragma once
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

// What two SkyCraft Link programs say to each other. Every transport message carries one frame:
// a type byte and little-endian fields. Channel 0 (reliable, ordered) carries the session and the
// TCP streams (Minecraft), channel 1 (unreliable) the UDP datagrams (Skyrim Together).
namespace link
{
	inline constexpr std::uint16_t kProtocol = 1;
	inline constexpr int           kChannelStreams = 0;
	inline constexpr int           kChannelDatagrams = 1;
	// Stream data goes in pieces this big: well under any transport's message limit.
	inline constexpr std::size_t kChunk = 16000;
	// A datagram no transport would carry unfragmented is not a game packet.
	inline constexpr std::size_t kMaxDatagram = 1400 * 8;

	enum class Service : std::uint8_t
	{
		kMinecraft = 1,  // TCP, the host's Minecraft (LAN) server
		kTogether = 2,   // UDP, the host's Skyrim Together server
	};

	enum class Type : std::uint8_t
	{
		kHello = 1,      // version, role (1 host, 2 guest), the sender's name
		kKeepAlive = 2,
		kBye = 3,
		kOpen = 4,       // stream, service: the guest opened a connection
		kData = 5,       // stream, bytes
		kClose = 6,      // stream, reason
		kDatagram = 7,   // service, bytes
	};

	enum class CloseReason : std::uint8_t
	{
		kClosed = 0,       // the other end closed it
		kRefused = 1,      // the host's service didn't accept the connection
		kUnknownStream = 2,
	};

	struct Frame
	{
		Type                          type{};
		std::uint16_t                 version = 0;
		std::uint8_t                  role = 0;
		std::string                   name;
		std::uint32_t                 stream = 0;
		Service                       service{};
		CloseReason                   reason{};
		std::span<const std::uint8_t> bytes;  // kData / kDatagram payload, inside the decoded message
	};

	std::vector<std::uint8_t> EncodeHello(std::uint8_t a_role, const std::string& a_name);
	std::vector<std::uint8_t> EncodeKeepAlive();
	std::vector<std::uint8_t> EncodeBye();
	std::vector<std::uint8_t> EncodeOpen(std::uint32_t a_stream, Service a_service);
	std::vector<std::uint8_t> EncodeData(std::uint32_t a_stream, std::span<const std::uint8_t> a_bytes);
	std::vector<std::uint8_t> EncodeClose(std::uint32_t a_stream, CloseReason a_reason);
	std::vector<std::uint8_t> EncodeDatagram(Service a_service, std::span<const std::uint8_t> a_bytes);

	// Nothing malformed, truncated or oversized gets through: a message from the network is untrusted.
	std::optional<Frame> Decode(std::span<const std::uint8_t> a_message);
}
