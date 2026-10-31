#pragma once
#include <winsock2.h>
#include <ws2tcpip.h>

#include <cstdint>
#include <string>

// Small Winsock helpers. Every socket SkyCraft Link opens itself is non-blocking and on 127.0.0.1
// only: the games on this PC are its only local peers.
namespace link::net
{
	struct Winsock
	{
		Winsock();
		~Winsock();
		Winsock(const Winsock&) = delete;
		Winsock& operator=(const Winsock&) = delete;
	};

	sockaddr_in Loopback(std::uint16_t a_port);
	std::string ErrorText(int a_wsaError);

	// Listening TCP socket on 127.0.0.1:a_port (0: any free port). a_bound: the port it got.
	SOCKET Listen(std::uint16_t a_port, std::uint16_t* a_bound);
	// Starts a non-blocking connect to 127.0.0.1:a_port; it completes when the socket turns writable.
	SOCKET StartConnect(std::uint16_t a_port);
	// UDP socket bound to 127.0.0.1:a_port (0: any); a_connectTo != 0 also fixes its peer.
	SOCKET Udp(std::uint16_t a_port, std::uint16_t a_connectTo, std::uint16_t* a_bound);

	// The pending error of a socket (after a failed non-blocking connect), 0 if none.
	int PendingError(SOCKET a_socket);
	void Close(SOCKET& a_socket);
}
