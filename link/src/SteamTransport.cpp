#include "SteamTransport.h"

#include "Frames.h"

#include <algorithm>
#include <cstring>

namespace link
{
	using namespace steam;

	namespace
	{
		constexpr auto kPendingWait = std::chrono::seconds(15);
		constexpr int  kBatch = 64;

		bool Left(std::uint32_t a_state) { return (a_state & (kChatLeft | kChatDisconnected | kChatKicked | kChatBanned)) != 0; }

		std::string LobbyValue() { return std::to_string(kProtocol); }
	}

	std::unique_ptr<SteamTransport> SteamTransport::Start(std::string& a_error, std::vector<std::string>& a_report)
	{
		std::unique_ptr<SteamTransport> t(new SteamTransport());
		auto&                           api = t->api_;
		if (!api.Load(Candidates(), a_report)) {
			a_error = "no_steam_api";
			return nullptr;
		}
		a_report.push_back("steam_api64.dll: " + Utf8(api.path));
		if (!api.IsSteamRunning()) {
			a_error = "steam_not_running";
			return nullptr;
		}
		// Spacewar: Valve's test app that every Steam account has, so no game of ours has to exist.
		::SetEnvironmentVariableW(L"SteamAppId", L"480");
		::SetEnvironmentVariableW(L"SteamGameId", L"480");
		char       message[1024]{};
		const bool started = api.InitFlat ? api.InitFlat(message) == 0 : api.Init();
		if (!started) {
			a_error = "steam_init_failed";
			if (message[0]) {
				a_report.push_back(std::string("SteamAPI_Init: ") + message);
			}
			return nullptr;
		}
		t->initialized_ = true;
		api.ManualDispatch_Init();
		t->pipe_ = api.GetHSteamPipe();
		t->matchmaking_ = api.SteamMatchmaking();
		t->friends_ = api.SteamFriends();
		t->user_ = api.SteamUser();
		t->messages_ = api.SteamNetworkingMessages();
		t->utils_ = api.SteamNetworkingUtils();
		if (!t->matchmaking_ || !t->friends_ || !t->user_ || !t->messages_ || !t->utils_) {
			a_error = "steam_interfaces";
			return nullptr;
		}
		if (!api.BLoggedOn(t->user_)) {
			a_error = "steam_offline";
			return nullptr;
		}
		t->self_ = api.GetSteamID(t->user_);
		// A loading Minecraft world is megabytes at once: room for that before Send says "later".
		api.SetGlobalConfigValueInt32(t->utils_, kConfigSendBufferSize, 8 << 20);
		api.InitRelayNetworkAccess(t->utils_);
		return t;
	}

	SteamTransport::~SteamTransport()
	{
		if (initialized_) {
			if (lobby_) {
				api_.LeaveLobby(matchmaking_, lobby_);
			}
			for (const auto& [peer, identity] : identities_) {
				api_.CloseSessionWithUser(messages_, identity);
			}
			api_.Shutdown();
		}
		api_.Unload();
	}

	void SteamTransport::Host(int a_maxMembers)
	{
		hosting_ = true;
		phase_ = Phase::kCreating;
		call_ = api_.CreateLobby(matchmaking_, kLobbyFriendsOnly, a_maxMembers);
		if (call_ == 0) {
			Failed("create_lobby_failed");
		}
	}

	void SteamTransport::Join(SteamID a_lobby)
	{
		hosting_ = false;
		phase_ = Phase::kJoining;
		call_ = api_.JoinLobby(matchmaking_, a_lobby);
		if (call_ == 0) {
			Failed("join_failed");
		}
	}

	std::vector<SteamTransport::Friend> SteamTransport::Friends(std::chrono::milliseconds a_wait)
	{
		std::vector<Friend> out;
		const int           count = api_.GetFriendCount(friends_, kFriendFlagImmediate);
		for (int i = 0; i < count; ++i) {
			const SteamID  id = api_.GetFriendByIndex(friends_, i, kFriendFlagImmediate);
			FriendGameInfo game{};
			if (!api_.GetFriendGamePlayed(friends_, id, &game) || (game.game & 0xFFFFFF) != kSpacewar || game.lobby == 0) {
				continue;
			}
			const char* name = api_.GetFriendPersonaName(friends_, id);
			out.push_back({ id, name ? name : "", game.lobby, false });
			api_.RequestLobbyData(matchmaking_, game.lobby);
		}
		const auto until = Clock::now() + a_wait;
		auto       answered = [&] { return std::ranges::all_of(out, [&](const Friend& a_f) { return answered_.contains(a_f.lobby); }); };
		while (!out.empty() && !answered() && Clock::now() < until) {
			RunCallbacks();
			::Sleep(10);
		}
		for (auto& f : out) {
			const char* key = api_.GetLobbyData(matchmaking_, f.lobby, kLobbyKey);
			f.skycraft = key && *key;
		}
		return out;
	}

	std::string SteamTransport::PersonaName()
	{
		const char* name = api_.GetPersonaName(friends_);
		return name ? name : "";
	}

	PeerId SteamTransport::HostPeer() { return !hosting_ && phase_ == Phase::kInLobby ? owner_ : 0; }

	std::string SteamTransport::Name(PeerId a_peer)
	{
		const char* name = api_.GetFriendPersonaName(friends_, a_peer);
		return name && *name ? name : std::to_string(a_peer);
	}

	bool SteamTransport::Send(PeerId a_peer, int a_channel, std::span<const std::uint8_t> a_data, bool a_reliable)
	{
		const int flags = (a_reliable ? kSendReliable : kSendUnreliable) | kSendNoNagle | kSendAutoRestartBrokenSession;
		const int result = api_.SendMessageToUser(messages_, IdentityOf(a_peer), a_data.data(), static_cast<std::uint32_t>(a_data.size()), flags, a_channel);
		return result != kResultLimitExceeded;  // anything else is sent, or lost the way a datagram is
	}

	void SteamTransport::Pump(std::vector<Message>& a_out)
	{
		RunCallbacks();
		if (!pending_.empty()) {
			AcceptPending();
		}
		for (const int channel : { kChannelStreams, kChannelDatagrams }) {
			void* batch[kBatch];
			for (;;) {
				const int got = api_.ReceiveMessagesOnChannel(messages_, channel, batch, kBatch);
				for (int i = 0; i < got; ++i) {
					auto*         m = static_cast<std::uint8_t*>(batch[i]);
					const auto*   data = *reinterpret_cast<const std::uint8_t* const*>(m + kMessageData);
					const int     size = *reinterpret_cast<const int*>(m + kMessageSize);
					const SteamID from = api_.GetSteamID64(m + kMessageSender);
					if (from && data && size > 0) {
						a_out.push_back(Message{ from, channel, std::vector<std::uint8_t>(data, data + size) });
					}
					api_.ReleaseMessage(m);
				}
				if (got < kBatch) {
					break;
				}
			}
		}
	}

	void SteamTransport::TakeLost(std::vector<PeerId>& a_out)
	{
		for (const PeerId peer : lost_) {
			if (std::ranges::find(a_out, peer) == a_out.end()) {
				a_out.push_back(peer);
			}
		}
		lost_.clear();
	}

	void SteamTransport::Forget(PeerId a_peer)
	{
		if (const auto it = identities_.find(a_peer); it != identities_.end()) {
			api_.CloseSessionWithUser(messages_, it->second);
			identities_.erase(it);
		}
		pending_.erase(a_peer);
	}

	void SteamTransport::RunCallbacks()
	{
		api_.ManualDispatch_RunFrame(pipe_);
		CallbackMsg message{};
		while (api_.ManualDispatch_GetNextCallback(pipe_, &message)) {
			if (message.callback == kAPICallCompleted && message.paramSize >= static_cast<int>(sizeof(APICallCompleted))) {
				APICallCompleted done{};
				std::memcpy(&done, message.param, sizeof(done));
				std::vector<std::uint8_t> result(done.paramSize);
				bool                      failed = true;
				if (api_.ManualDispatch_GetAPICallResult(pipe_, done.call, result.data(), static_cast<int>(result.size()), done.callback, &failed) && !failed) {
					OnCallback(done.callback, result.data(), result.size());
				} else if (done.call == call_ && (phase_ == Phase::kCreating || phase_ == Phase::kJoining)) {
					Failed(phase_ == Phase::kCreating ? "create_lobby_failed" : "join_failed");
				}
			} else if (message.paramSize >= 0) {
				OnCallback(message.callback, message.param, static_cast<std::size_t>(message.paramSize));
			}
			api_.ManualDispatch_FreeLastCallback(pipe_);
		}
	}

	void SteamTransport::OnCallback(int a_id, const std::uint8_t* a_param, std::size_t a_size)
	{
		auto read = [&]<class T>(T& a_out) {
			if (!a_param || a_size < sizeof(T)) {
				return false;
			}
			std::memcpy(&a_out, a_param, sizeof(T));
			return true;
		};
		switch (a_id) {
		case kLobbyCreated:
			{
				LobbyCreated created{};
				if (!read(created) || phase_ != Phase::kCreating) {
					break;
				}
				if (created.result != kResultOK) {
					Failed("create_lobby_failed");
					break;
				}
				lobby_ = created.lobby;
				owner_ = self_;
				api_.SetLobbyData(matchmaking_, lobby_, kLobbyKey, LobbyValue().c_str());
				phase_ = Phase::kInLobby;
				break;
			}
		case kLobbyEnter:
			{
				LobbyEnter entered{};
				if (!read(entered) || phase_ != Phase::kJoining) {
					break;  // the host gets one for its own new lobby as well
				}
				if (entered.response != kChatEnterSuccess) {
					Failed("join_refused");
					break;
				}
				lobby_ = entered.lobby;
				const char* key = api_.GetLobbyData(matchmaking_, lobby_, kLobbyKey);
				if (!key || !*key) {
					Failed("not_skycraft");
					break;
				}
				owner_ = api_.GetLobbyOwner(matchmaking_, lobby_);
				phase_ = owner_ ? Phase::kInLobby : Phase::kFailed;
				if (!owner_) {
					failure_ = "join_failed";
				}
				break;
			}
		case kLobbyChatUpdate:
			{
				LobbyChatUpdate update{};
				if (!read(update) || update.lobby != lobby_ || !Left(update.state)) {
					break;
				}
				if (hosting_ && update.changed != self_) {
					lost_.push_back(update.changed);
				} else if (!hosting_ && update.changed == owner_) {
					lost_.push_back(owner_);
				}
				break;
			}
		case kLobbyDataUpdate:
			{
				LobbyDataUpdate update{};
				if (read(update) && update.member == update.lobby) {
					answered_.insert(update.lobby);
				}
				break;
			}
		case kSessionRequest:
			{
				Identity identity{};
				if (!read(identity)) {
					break;
				}
				if (const SteamID who = api_.GetSteamID64(&identity); who != 0) {
					pending_.try_emplace(who, Clock::now());
					AcceptPending();
				}
				break;
			}
		case kSessionFailed:
			{
				Identity identity{};  // SteamNetConnectionInfo_t starts with the remote identity
				if (!read(identity)) {
					break;
				}
				if (const SteamID who = api_.GetSteamID64(&identity); who != 0 && identities_.contains(who)) {
					lost_.push_back(who);
				}
				break;
			}
		default:
			break;
		}
	}

	void SteamTransport::AcceptPending()
	{
		const auto now = Clock::now();
		for (auto it = pending_.begin(); it != pending_.end();) {
			const SteamID who = it->first;
			// Host: a member of its lobby (they may show up in it a moment after asking). Guest: the host.
			const bool allowed = phase_ == Phase::kInLobby && (hosting_ ? Member(who) : who == owner_);
			if (allowed) {
				api_.AcceptSessionWithUser(messages_, IdentityOf(who));
				it = pending_.erase(it);
			} else if (now - it->second > kPendingWait) {
				it = pending_.erase(it);  // a stranger: Steam drops what they sent
			} else {
				++it;
			}
		}
	}

	bool SteamTransport::Member(SteamID a_who)
	{
		const int count = api_.GetNumLobbyMembers(matchmaking_, lobby_);
		for (int i = 0; i < count; ++i) {
			if (api_.GetLobbyMemberByIndex(matchmaking_, lobby_, i) == a_who) {
				return true;
			}
		}
		return false;
	}

	const Identity& SteamTransport::IdentityOf(SteamID a_who)
	{
		auto [it, fresh] = identities_.try_emplace(a_who);
		if (fresh) {
			api_.SetSteamID64(&it->second, a_who);
		}
		return it->second;
	}

	void SteamTransport::Failed(std::string a_code)
	{
		phase_ = Phase::kFailed;
		failure_ = std::move(a_code);
	}
}
