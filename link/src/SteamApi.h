#pragma once
#include <windows.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Valve's Steamworks flat C API, loaded at run time from a steam_api64.dll that a Steam game on this
// PC already has (nothing of Valve's is shipped with SkyCraft). Only a file Valve signed is loaded.
// Layouts and callback numbers follow the Steamworks headers for 64-bit Windows (8-byte packing).
namespace link::steam
{
	using SteamID = std::uint64_t;
	using APICall = std::uint64_t;
	using Pipe = std::int32_t;

	inline constexpr std::uint32_t kSpacewar = 480;  // Valve's free test app: every Steam user has it

	// Callback ids (k_i...Callbacks + n).
	inline constexpr int kGameLobbyJoinRequested = 333;
	inline constexpr int kLobbyEnter = 504;
	inline constexpr int kLobbyDataUpdate = 505;
	inline constexpr int kLobbyChatUpdate = 506;
	inline constexpr int kLobbyCreated = 513;
	inline constexpr int kAPICallCompleted = 703;
	inline constexpr int kSessionRequest = 1251;
	inline constexpr int kSessionFailed = 1252;

	inline constexpr int kLobbyFriendsOnly = 1;
	inline constexpr int kFriendFlagImmediate = 4;
	inline constexpr int kResultOK = 1;
	inline constexpr int kResultLimitExceeded = 25;
	inline constexpr int kChatEnterSuccess = 1;
	inline constexpr int kChatLeft = 0x2, kChatDisconnected = 0x4, kChatKicked = 0x8, kChatBanned = 0x10;

	inline constexpr int kSendUnreliable = 0, kSendNoNagle = 1, kSendReliable = 8, kSendAutoRestartBrokenSession = 32;
	inline constexpr int kConfigSendBufferSize = 9;

	struct CallbackMsg  // CallbackMsg_t
	{
		std::int32_t  user;
		std::int32_t  callback;
		std::uint8_t* param;
		std::int32_t  paramSize;
	};
	static_assert(sizeof(CallbackMsg) == 24 && offsetof(CallbackMsg, param) == 8 && offsetof(CallbackMsg, paramSize) == 16);

	struct APICallCompleted  // SteamAPICallCompleted_t
	{
		APICall       call;
		std::int32_t  callback;
		std::uint32_t paramSize;
	};
	static_assert(sizeof(APICallCompleted) == 16);

	struct LobbyCreated  // LobbyCreated_t
	{
		std::int32_t result;
		SteamID      lobby;
	};
	static_assert(sizeof(LobbyCreated) == 16 && offsetof(LobbyCreated, lobby) == 8);

	struct LobbyEnter  // LobbyEnter_t
	{
		SteamID       lobby;
		std::uint32_t permissions;
		bool          locked;
		std::uint32_t response;
	};
	static_assert(sizeof(LobbyEnter) == 24 && offsetof(LobbyEnter, response) == 16);

	struct LobbyChatUpdate  // LobbyChatUpdate_t
	{
		SteamID       lobby;
		SteamID       changed;
		SteamID       by;
		std::uint32_t state;
	};
	static_assert(offsetof(LobbyChatUpdate, state) == 24);

	struct LobbyDataUpdate  // LobbyDataUpdate_t
	{
		SteamID      lobby;
		SteamID      member;  // the lobby itself when it is the lobby's own data
		std::uint8_t success;
	};
	static_assert(offsetof(LobbyDataUpdate, success) == 16);

	struct GameLobbyJoinRequested  // GameLobbyJoinRequested_t
	{
		SteamID lobby;
		SteamID friendId;
	};

	struct FriendGameInfo  // FriendGameInfo_t
	{
		std::uint64_t game;  // CGameID: app id in the low 24 bits
		std::uint32_t ip;
		std::uint16_t port;
		std::uint16_t queryPort;
		SteamID       lobby;
	};
	static_assert(sizeof(FriendGameInfo) == 24 && offsetof(FriendGameInfo, lobby) == 16);

	// SteamNetworkingIdentity is 136 opaque bytes, filled and read only through Valve's helpers.
	struct Identity
	{
		alignas(8) std::uint8_t bytes[136]{};
	};

	// SteamNetworkingMessage_t on 64-bit: data at 0, size at 8, the sender's identity at 16.
	inline constexpr std::size_t kMessageData = 0, kMessageSize = 8, kMessageSender = 16;

	struct Api
	{
		HMODULE      dll = nullptr;
		std::wstring path;

		int (*InitFlat)(char* a_error1024) = nullptr;  // ESteamAPIInitResult, 0 = OK
		bool (*Init)() = nullptr;                      // older DLLs without InitFlat
		void (*Shutdown)() = nullptr;
		bool (*IsSteamRunning)() = nullptr;
		Pipe (*GetHSteamPipe)() = nullptr;
		void (*ManualDispatch_Init)() = nullptr;
		void (*ManualDispatch_RunFrame)(Pipe) = nullptr;
		bool (*ManualDispatch_GetNextCallback)(Pipe, CallbackMsg*) = nullptr;
		void (*ManualDispatch_FreeLastCallback)(Pipe) = nullptr;
		bool (*ManualDispatch_GetAPICallResult)(Pipe, APICall, void*, int, int, bool*) = nullptr;

		void* (*SteamMatchmaking)() = nullptr;
		void* (*SteamFriends)() = nullptr;
		void* (*SteamUser)() = nullptr;
		void* (*SteamNetworkingMessages)() = nullptr;
		void* (*SteamNetworkingUtils)() = nullptr;

		APICall (*CreateLobby)(void*, int, int) = nullptr;
		APICall (*JoinLobby)(void*, SteamID) = nullptr;
		void (*LeaveLobby)(void*, SteamID) = nullptr;
		bool (*SetLobbyData)(void*, SteamID, const char*, const char*) = nullptr;
		const char* (*GetLobbyData)(void*, SteamID, const char*) = nullptr;
		SteamID (*GetLobbyOwner)(void*, SteamID) = nullptr;
		int (*GetNumLobbyMembers)(void*, SteamID) = nullptr;
		SteamID (*GetLobbyMemberByIndex)(void*, SteamID, int) = nullptr;
		bool (*InviteUserToLobby)(void*, SteamID, SteamID) = nullptr;
		bool (*RequestLobbyData)(void*, SteamID) = nullptr;

		int (*GetFriendCount)(void*, int) = nullptr;
		SteamID (*GetFriendByIndex)(void*, int, int) = nullptr;
		const char* (*GetFriendPersonaName)(void*, SteamID) = nullptr;
		bool (*GetFriendGamePlayed)(void*, SteamID, FriendGameInfo*) = nullptr;
		bool (*SetRichPresence)(void*, const char*, const char*) = nullptr;
		const char* (*GetPersonaName)(void*) = nullptr;

		SteamID (*GetSteamID)(void*) = nullptr;
		bool (*BLoggedOn)(void*) = nullptr;

		int (*SendMessageToUser)(void*, const Identity&, const void*, std::uint32_t, int, int) = nullptr;
		int (*ReceiveMessagesOnChannel)(void*, int, void**, int) = nullptr;
		bool (*AcceptSessionWithUser)(void*, const Identity&) = nullptr;
		bool (*CloseSessionWithUser)(void*, const Identity&) = nullptr;
		void (*ReleaseMessage)(void*) = nullptr;
		bool (*SetGlobalConfigValueInt32)(void*, int, std::int32_t) = nullptr;
		void (*InitRelayNetworkAccess)(void*) = nullptr;
		void (*SetSteamID64)(Identity*, SteamID) = nullptr;
		SteamID (*GetSteamID64)(const void*) = nullptr;

		// Loads the first usable DLL among a_candidates; a_report says why each other one wasn't.
		bool Load(const std::vector<std::wstring>& a_candidates, std::vector<std::string>& a_report);
		void Unload();
	};

	// steam_api64.dll files to try, best first: next to this program, then every Steam game's.
	std::vector<std::wstring> Candidates();
	// Signed by Valve with a valid, trusted signature.
	bool SignedByValve(const std::wstring& a_path);
	std::string Utf8(const std::wstring& a_text);
}
