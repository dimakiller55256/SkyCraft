#include "Net.h"

#include <mstcpip.h>

#include <system_error>

namespace link::net
{
	namespace
	{
		bool NonBlocking(SOCKET a_socket)
		{
			u_long on = 1;
			return ::ioctlsocket(a_socket, FIONBIO, &on) == 0;
		}

		std::uint16_t BoundPort(SOCKET a_socket)
		{
			sockaddr_in addr{};
			int         len = sizeof(addr);
			return ::getsockname(a_socket, reinterpret_cast<sockaddr*>(&addr), &len) == 0 ? ntohs(addr.sin_port) : 0;
		}
	}

	Winsock::Winsock()
	{
		WSADATA data{};
		if (::WSAStartup(MAKEWORD(2, 2), &data) != 0) {
			throw std::runtime_error("Winsock failed to start");
		}
	}

	Winsock::~Winsock() { ::WSACleanup(); }

	sockaddr_in Loopback(std::uint16_t a_port)
	{
		sockaddr_in addr{};
		addr.sin_family = AF_INET;
		addr.sin_port = htons(a_port);
		addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
		return addr;
	}

	std::string ErrorText(int a_wsaError) { return std::system_category().message(a_wsaError) + " (" + std::to_string(a_wsaError) + ")"; }

	SOCKET Listen(std::uint16_t a_port, std::uint16_t* a_bound)
	{
		SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
		if (s == INVALID_SOCKET) {
			return s;
		}
		// Exclusive: another program must not share the port our friend's game connects to.
		BOOL exclusive = TRUE;
		::setsockopt(s, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<const char*>(&exclusive), sizeof(exclusive));
		const auto addr = Loopback(a_port);
		if (::bind(s, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0 || ::listen(s, SOMAXCONN) != 0 || !NonBlocking(s)) {
			Close(s);
			return INVALID_SOCKET;
		}
		if (a_bound) {
			*a_bound = BoundPort(s);
		}
		return s;
	}

	SOCKET StartConnect(std::uint16_t a_port)
	{
		SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
		if (s == INVALID_SOCKET || !NonBlocking(s)) {
			Close(s);
			return INVALID_SOCKET;
		}
		BOOL noDelay = TRUE;
		::setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&noDelay), sizeof(noDelay));
		const auto addr = Loopback(a_port);
		if (::connect(s, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0 && ::WSAGetLastError() != WSAEWOULDBLOCK) {
			Close(s);
			return INVALID_SOCKET;
		}
		return s;
	}

	SOCKET Udp(std::uint16_t a_port, std::uint16_t a_connectTo, std::uint16_t* a_bound)
	{
		SOCKET s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
		if (s == INVALID_SOCKET) {
			return s;
		}
		// A datagram to a closed local port makes Windows fail the next receive (WSAECONNRESET).
		BOOL  off = FALSE;
		DWORD unused = 0;
		::WSAIoctl(s, SIO_UDP_CONNRESET, &off, sizeof(off), nullptr, 0, &unused, nullptr, nullptr);
		int buffer = 4 << 20;
		::setsockopt(s, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&buffer), sizeof(buffer));
		::setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&buffer), sizeof(buffer));
		const auto addr = Loopback(a_port);
		if (::bind(s, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0 || !NonBlocking(s)) {
			Close(s);
			return INVALID_SOCKET;
		}
		if (a_connectTo) {
			const auto to = Loopback(a_connectTo);
			if (::connect(s, reinterpret_cast<const sockaddr*>(&to), sizeof(to)) != 0) {
				Close(s);
				return INVALID_SOCKET;
			}
		}
		if (a_bound) {
			*a_bound = BoundPort(s);
		}
		return s;
	}

	int PendingError(SOCKET a_socket)
	{
		int error = 0;
		int len = sizeof(error);
		return ::getsockopt(a_socket, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &len) == 0 ? error : ::WSAGetLastError();
	}

	void Close(SOCKET& a_socket)
	{
		if (a_socket != INVALID_SOCKET) {
			::closesocket(a_socket);
			a_socket = INVALID_SOCKET;
		}
	}
}
