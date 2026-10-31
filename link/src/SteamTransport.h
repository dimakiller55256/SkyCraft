#pragma once
#include "SteamApi.h"
#include "Transport.h"

#include <chrono>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

namespace link
{
	// Between Steam friends: the host opens a friends-only lobby of Spacewar (AppID 480), a guest
	// joins it, and frames travel as Steam networking messages through Valve's relays: no open
	// ports, no VPN, no IP addresses shown. Only members of the host's lobby get a session.
	class SteamTransport final : public Transport
	{
	public:
		using Clock = std::chrono::steady_clock;

		enum class Phase
		{
			kIdle,
			kCreating,  // host: the lobby is on its way
			kJoining,   // guest: entering the friend's lobby
			kInLobby,
			kFailed,
		};

		struct Friend
		{
			steam::SteamID id = 0;
			std::string    name;
			steam::SteamID lobby = 0;
			bool           skycraft = false;  // the lobby is SkyCraft Link's, not some other Spacewar user's
		};

		static constexpr const char* kLobbyKey = "skycraft_link";

		// Null, with a code in a_error, when there's no usable Steam; a_report says what was tried.
		static std::unique_ptr<SteamTransport> Start(std::string& a_error, std::vector<std::string>& a_report);
		~SteamTransport() override;
		SteamTransport(const SteamTransport&) = delete;
		SteamTransport& operator=(const SteamTransport&) = delete;

		void Host(int a_maxMembers);
		void Join(steam::SteamID a_lobby);
		// Friends now in a Spacewar lobby; waits up to a_wait to learn which lobbies are SkyCraft's.
		std::vector<Friend> Friends(std::chrono::milliseconds a_wait);

		Phase              phase() const { return phase_; }
		const std::string& failure() const { return failure_; }
		steam::SteamID     lobby() const { return lobby_; }
		std::string        PersonaName();
		const std::wstring& Dll() const { return api_.path; }

		PeerId      Self() const override { return self_; }
		PeerId      HostPeer() override;
		std::string Name(PeerId a_peer) override;
		bool        Send(PeerId a_peer, int a_channel, std::span<const std::uint8_t> a_data, bool a_reliable) override;
		void        Pump(std::vector<Message>& a_out) override;
		void        TakeLost(std::vector<PeerId>& a_out) override;
		void        Forget(PeerId a_peer) override;

	private:
		SteamTransport() = default;
		void                   RunCallbacks();
		void                   OnCallback(int a_id, const std::uint8_t* a_param, std::size_t a_size);
		void                   AcceptPending();
		bool                   Member(steam::SteamID a_who);
		const steam::Identity& IdentityOf(steam::SteamID a_who);
		void                   Failed(std::string a_code);

		steam::Api                                  api_;
		bool                                        initialized_ = false;
		steam::Pipe                                 pipe_ = 0;
		void*                                       matchmaking_ = nullptr;
		void*                                       friends_ = nullptr;
		void*                                       user_ = nullptr;
		void*                                       messages_ = nullptr;
		void*                                       utils_ = nullptr;
		steam::SteamID                              self_ = 0;
		bool                                        hosting_ = false;
		Phase                                       phase_ = Phase::kIdle;
		std::string                                 failure_;
		steam::APICall                              call_ = 0;
		steam::SteamID                              lobby_ = 0;
		steam::SteamID                              owner_ = 0;
		std::map<steam::SteamID, steam::Identity>   identities_;
		std::map<steam::SteamID, Clock::time_point> pending_;   // asked for a session, not (yet) allowed
		std::set<steam::SteamID>                    answered_;  // lobbies whose data arrived
		std::vector<PeerId>                         lost_;
	};
}
