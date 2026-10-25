#include "Frames.h"

namespace link
{
	namespace
	{
		constexpr std::size_t kMaxName = 64;

		void Put16(std::vector<std::uint8_t>& a_out, std::uint16_t a_v)
		{
			a_out.push_back(static_cast<std::uint8_t>(a_v));
			a_out.push_back(static_cast<std::uint8_t>(a_v >> 8));
		}

		void Put32(std::vector<std::uint8_t>& a_out, std::uint32_t a_v)
		{
			for (int i = 0; i < 4; ++i) {
				a_out.push_back(static_cast<std::uint8_t>(a_v >> (8 * i)));
			}
		}

		std::uint16_t Get16(const std::uint8_t* a_p) { return static_cast<std::uint16_t>(a_p[0] | (a_p[1] << 8)); }

		std::uint32_t Get32(const std::uint8_t* a_p)
		{
			return static_cast<std::uint32_t>(a_p[0]) | (static_cast<std::uint32_t>(a_p[1]) << 8) | (static_cast<std::uint32_t>(a_p[2]) << 16) |
			       (static_cast<std::uint32_t>(a_p[3]) << 24);
		}

		std::vector<std::uint8_t> Start(Type a_type, std::size_t a_reserve)
		{
			std::vector<std::uint8_t> out;
			out.reserve(1 + a_reserve);
			out.push_back(static_cast<std::uint8_t>(a_type));
			return out;
		}
	}

	std::vector<std::uint8_t> EncodeHello(std::uint8_t a_role, const std::string& a_name)
	{
		const std::string name = a_name.substr(0, kMaxName);
		auto              out = Start(Type::kHello, 4 + name.size());
		Put16(out, kProtocol);
		out.push_back(a_role);
		out.push_back(static_cast<std::uint8_t>(name.size()));
		out.insert(out.end(), name.begin(), name.end());
		return out;
	}

	std::vector<std::uint8_t> EncodeKeepAlive() { return Start(Type::kKeepAlive, 0); }

	std::vector<std::uint8_t> EncodeBye() { return Start(Type::kBye, 0); }

	std::vector<std::uint8_t> EncodeOpen(std::uint32_t a_stream, Service a_service)
	{
		auto out = Start(Type::kOpen, 5);
		Put32(out, a_stream);
		out.push_back(static_cast<std::uint8_t>(a_service));
		return out;
	}

	std::vector<std::uint8_t> EncodeData(std::uint32_t a_stream, std::span<const std::uint8_t> a_bytes)
	{
		auto out = Start(Type::kData, 4 + a_bytes.size());
		Put32(out, a_stream);
		out.insert(out.end(), a_bytes.begin(), a_bytes.end());
		return out;
	}

	std::vector<std::uint8_t> EncodeClose(std::uint32_t a_stream, CloseReason a_reason)
	{
		auto out = Start(Type::kClose, 5);
		Put32(out, a_stream);
		out.push_back(static_cast<std::uint8_t>(a_reason));
		return out;
	}

	std::vector<std::uint8_t> EncodeDatagram(Service a_service, std::span<const std::uint8_t> a_bytes)
	{
		auto out = Start(Type::kDatagram, 1 + a_bytes.size());
		out.push_back(static_cast<std::uint8_t>(a_service));
		out.insert(out.end(), a_bytes.begin(), a_bytes.end());
		return out;
	}

	std::optional<Frame> Decode(std::span<const std::uint8_t> a_message)
	{
		if (a_message.empty()) {
			return std::nullopt;
		}
		Frame             f;
		const auto*       p = a_message.data() + 1;
		const std::size_t n = a_message.size() - 1;
		auto              validService = [](std::uint8_t a_s) { return a_s == 1 || a_s == 2; };
		f.type = static_cast<Type>(a_message[0]);
		switch (f.type) {
		case Type::kHello:
			{
				if (n < 4 || n != 4u + p[3] || p[3] > kMaxName || (p[2] != 1 && p[2] != 2)) {
					return std::nullopt;
				}
				f.version = Get16(p);
				f.role = p[2];
				f.name.assign(reinterpret_cast<const char*>(p + 4), p[3]);
				return f;
			}
		case Type::kKeepAlive:
		case Type::kBye:
			return n == 0 ? std::optional<Frame>(f) : std::nullopt;
		case Type::kOpen:
			if (n != 5 || !validService(p[4])) {
				return std::nullopt;
			}
			f.stream = Get32(p);
			f.service = static_cast<Service>(p[4]);
			return f;
		case Type::kData:
			if (n < 5 || n - 4 > kChunk) {
				return std::nullopt;
			}
			f.stream = Get32(p);
			f.bytes = a_message.subspan(5);
			return f;
		case Type::kClose:
			if (n != 5 || p[4] > 2) {
				return std::nullopt;
			}
			f.stream = Get32(p);
			f.reason = static_cast<CloseReason>(p[4]);
			return f;
		case Type::kDatagram:
			if (n < 2 || n - 1 > kMaxDatagram || !validService(p[0])) {
				return std::nullopt;
			}
			f.service = static_cast<Service>(p[0]);
			f.bytes = a_message.subspan(2);
			return f;
		default:
			return std::nullopt;
		}
	}
}
