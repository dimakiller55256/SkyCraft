// SkyCraft Link: a friend's Minecraft and Skyrim Together reach the host's servers through Steam
// (Spacewar's relays) instead of a VPN. Started by the Minecraft side, which reads its JSON lines.
#include "LoopbackTransport.h"
#include "Net.h"
#include "Status.h"
#include "SteamApi.h"
#include "SteamTransport.h"
#include "Tunnel.h"

#include <timeapi.h>

#include <algorithm>
#include <atomic>
#include <charconv>
#include <cstdio>
#include <iterator>
#include <optional>
#include <string_view>
#include <thread>

namespace
{
	using namespace link;
	using namespace std::chrono_literals;
	using Clock = std::chrono::steady_clock;

	constexpr int  kMaxMembers = 8;
	constexpr auto kJoinWait = 30s;
	constexpr auto kGoodbye = 300ms;

	constexpr const char* kUsage =
		"SkyCraft Link: Minecraft и Skyrim Together между друзьями через Steam (Spacewar).\n"
		"  SkyCraftLink host [--minecraft-port 25565] [--together-port 10578] [--no-together]\n"
		"  SkyCraftLink join [auto|<SteamID друга>] [--minecraft-port 25565] [--together-port 10578] [--no-together]\n"
		"  SkyCraftLink friends   друзья, которые открыли мир\n"
		"  SkyCraftLink probe     какая steam_api64.dll подойдёт (Steam не трогается)\n"
		"Ещё: --parent-pid <PID> (закрыться вместе с этим процессом), --stdin-control (строка stop или конец\n"
		"stdin завершают работу), --loopback <порт> (проверка на одном ПК без Steam), --wait-ms <мс> (сколько\n"
		"ждать сведений о лобби друзей), --name <имя>.\n";

	std::atomic<bool> g_stop{ false };

	BOOL WINAPI OnConsole(DWORD)
	{
		g_stop = true;
		return TRUE;
	}

	struct Options
	{
		std::string   mode;             // host, join, friends, probe
		std::string   target = "auto";  // join: a friend's Steam ID, or the only friend hosting
		std::uint16_t minecraftPort = 25565;
		std::uint16_t togetherPort = 10578;
		bool          together = true;
		std::uint16_t loopback = 0;  // tests: a local TCP link instead of Steam
		DWORD         parent = 0;    // end along with this process (the Minecraft that started us)
		bool          stdinControl = false;
		int           waitMs = 3000;
		std::string   name;
	};

	template <class T>
	std::optional<T> Number(std::string_view a_text)
	{
		T          value{};
		const auto end = a_text.data() + a_text.size();
		const auto [at, error] = std::from_chars(a_text.data(), end, value);
		if (error != std::errc{} || at != end) {
			return std::nullopt;
		}
		return value;
	}

	const char* Text(std::string_view a_code)
	{
		struct Entry
		{
			std::string_view code;
			const char*      text;
		};
		static constexpr Entry kTexts[] = {
			{ "no_steam_api", "Не найдена подходящая steam_api64.dll: установите бесплатный SteamVR или любую игру Steam, обновлявшуюся после 2020 года." },
			{ "steam_not_running", "Steam не запущен: запустите Steam и войдите в аккаунт." },
			{ "steam_init_failed", "Steam не дал подключиться к Spacewar (AppID 480)." },
			{ "steam_interfaces", "Steam не выдал нужные интерфейсы этой steam_api64.dll." },
			{ "steam_offline", "Steam в автономном режиме: нужно войти в сеть." },
			{ "create_lobby_failed", "Steam не создал лобби. Попробуйте ещё раз." },
			{ "join_failed", "Не удалось войти в лобби друга." },
			{ "join_refused", "Лобби друга не пустило: оно закрыто или заполнено." },
			{ "join_timeout", "Steam слишком долго не пускает в лобби друга." },
			{ "not_skycraft", "Это лобби Spacewar не от SkyCraft." },
			{ "no_hosts", "Никто из друзей в Steam сейчас не открыл мир через SkyCraft." },
			{ "several_hosts", "Мир открыли несколько друзей: выберите одного." },
			{ "friend_not_hosting", "Этот друг сейчас не открыл мир через SkyCraft." },
			{ "loopback", "Не удалось открыть локальный порт для проверки." },
			{ "parent_gone", "Minecraft, который запустил SkyCraft Link, уже закрыт." },
		};
		for (const auto& entry : kTexts) {
			if (entry.code == a_code) {
				return entry.text;
			}
		}
		return "Внутренняя ошибка SkyCraft Link.";
	}

	void Error(std::string_view a_code, std::string_view a_detail = {})
	{
		Event event("error");
		event.Str("code", a_code).Str("text", Text(a_code));
		if (!a_detail.empty()) {
			event.Str("detail", a_detail);
		}
		event.Send();
	}

	bool Parse(int a_argc, char** a_argv, Options& a_options)
	{
		if (a_argc < 2) {
			return false;
		}
		a_options.mode = a_argv[1];
		if (a_options.mode != "host" && a_options.mode != "join" && a_options.mode != "friends" && a_options.mode != "probe") {
			return false;
		}
		int i = 2;
		if (a_options.mode == "join" && i < a_argc && a_argv[i][0] != '-') {
			a_options.target = a_argv[i++];
		}
		for (; i < a_argc; ++i) {
			const std::string_view arg = a_argv[i];
			const bool             more = i + 1 < a_argc;
			auto                   value = [&]<class T>(T& a_out) {
				const auto parsed = more ? Number<T>(a_argv[++i]) : std::optional<T>{};
				if (parsed) {
					a_out = *parsed;
				}
				return parsed.has_value();
			};
			bool ok = true;
			if (arg == "--minecraft-port") {
				ok = value(a_options.minecraftPort);
			} else if (arg == "--together-port") {
				ok = value(a_options.togetherPort);
			} else if (arg == "--loopback") {
				ok = value(a_options.loopback) && a_options.loopback != 0;
			} else if (arg == "--no-together") {
				a_options.together = false;
			} else if (arg == "--stdin-control") {
				a_options.stdinControl = true;
			} else if (arg == "--parent-pid") {
				ok = value(a_options.parent);
			} else if (arg == "--wait-ms") {
				ok = value(a_options.waitMs) && a_options.waitMs >= 0;
			} else if (arg == "--name" && more) {
				a_options.name = a_argv[++i];
			} else {
				ok = false;
			}
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	// Which steam_api64.dll SkyCraft Link would use, without starting Steam or touching the account.
	int Probe()
	{
		const auto candidates = steam::Candidates();
		for (const auto& path : candidates) {
			Event("candidate").Str("path", steam::Utf8(path)).Flag("valve", steam::SignedByValve(path)).Send();
		}
		steam::Api               api;
		std::vector<std::string> report;
		const bool               usable = api.Load(candidates, report);
		for (const auto& line : report) {
			Event("log").Str("text", line).Send();
		}
		Event("probe")
			.Flag("usable", usable)
			.Str("dll", usable ? steam::Utf8(api.path) : "")
			.Flag("init_flat", usable && api.InitFlat != nullptr)
			.Flag("steam_running", usable && api.IsSteamRunning())
			.Send();
		api.Unload();
		return usable ? 0 : 2;
	}

	std::string FriendsJson(const std::vector<SteamTransport::Friend>& a_friends)
	{
		std::string json = "[";
		for (const auto& f : a_friends) {
			if (json.size() > 1) {
				json += ',';
			}
			json += "{\"id\":" + Quote(std::to_string(f.id)) + ",\"name\":" + Quote(f.name) + ",\"lobby\":" + Quote(std::to_string(f.lobby)) + '}';
		}
		return json + ']';
	}

	// Steam: picks the friend to join (or lists them for "friends"); false when there's nothing to run.
	bool Choose(const Options& a_options, SteamTransport& a_steam, int& a_code)
	{
		const auto                          friends = a_steam.Friends(std::chrono::milliseconds(a_options.waitMs));
		std::vector<SteamTransport::Friend> hosts;
		std::ranges::copy_if(friends, std::back_inserter(hosts), [](const auto& a_f) { return a_f.skycraft; });
		const auto others = static_cast<std::int64_t>(friends.size() - hosts.size());
		if (a_options.mode == "friends") {
			Event("friends").Json("hosts", FriendsJson(hosts)).Num("others", others).Send();
			a_code = 0;
			return false;
		}
		const SteamTransport::Friend* chosen = nullptr;
		if (a_options.target == "auto") {
			if (hosts.size() != 1) {
				Event("friends").Json("hosts", FriendsJson(hosts)).Num("others", others).Send();
				Error(hosts.empty() ? "no_hosts" : "several_hosts");
				a_code = 3;
				return false;
			}
			chosen = &hosts.front();
		} else {
			const auto id = Number<std::uint64_t>(a_options.target);
			const auto it = std::ranges::find_if(friends, [&](const auto& a_f) { return id && a_f.id == *id; });
			if (it == friends.end()) {
				Error("friend_not_hosting", a_options.target);
				a_code = 3;
				return false;
			}
			chosen = &*it;
		}
		Event("joining").Str("host", chosen->name).Str("id", std::to_string(chosen->id)).Send();
		a_steam.Join(chosen->lobby);
		return true;
	}

	int Run(const Options& a_options, HANDLE a_parent)
	{
		const bool                 host = a_options.mode == "host";
		std::unique_ptr<Transport> transport;
		SteamTransport*            steam = nullptr;
		std::string                name = a_options.name;
		if (a_options.loopback) {
			if (host) {
				std::string error;
				auto        link = LoopbackTransport::Host(a_options.loopback, error);
				if (!link) {
					Error("loopback", error);
					return 2;
				}
				transport = std::move(link);
			} else {
				transport = LoopbackTransport::Guest(a_options.loopback);
			}
			if (name.empty()) {
				name = host ? "host" : "guest";
			}
		} else {
			std::string              error;
			std::vector<std::string> report;
			auto                     link = SteamTransport::Start(error, report);
			for (const auto& line : report) {
				Event("log").Str("text", line).Send();
			}
			if (!link) {
				Error(error);
				return 2;
			}
			if (name.empty()) {
				name = link->PersonaName();
			}
			Event("steam").Str("self", std::to_string(link->Self())).Str("name", link->PersonaName()).Send();
			if (host) {
				link->Host(kMaxMembers);
			} else if (int code = 0; !Choose(a_options, *link, code)) {
				return code;
			}
			steam = link.get();
			transport = std::move(link);
		}

		TunnelOptions options;
		options.host = host;
		options.name = name;
		options.minecraftPort = a_options.minecraftPort;
		options.togetherPort = a_options.togetherPort;
		options.together = a_options.together;
		Tunnel tunnel(*transport, options);

		const auto             started = Clock::now();
		bool                   announced = false;
		int                    code = 0;
		std::vector<WSAPOLLFD> fds;
		while (!g_stop) {
			if (a_parent && ::WaitForSingleObject(a_parent, 0) == WAIT_OBJECT_0) {
				break;
			}
			fds.clear();
			tunnel.AddSockets(fds);
			transport->AddSockets(fds);
			const int wait = steam ? 2 : 20;  // Steam's messages have no socket to wake us
			if (fds.empty()) {
				::Sleep(wait);
			} else {
				::WSAPoll(fds.data(), static_cast<ULONG>(fds.size()), wait);
			}
			const auto now = Clock::now();
			tunnel.Pump(now);
			if (steam) {
				const auto phase = steam->phase();
				if (phase == SteamTransport::Phase::kFailed) {
					Error(steam->failure());
					code = 3;
					break;
				}
				if (phase == SteamTransport::Phase::kInLobby && !announced) {
					announced = true;
					Event(host ? "hosting" : "joined").Str("lobby", std::to_string(steam->lobby())).Str("name", name).Send();
				}
				if (phase == SteamTransport::Phase::kJoining && now - started > kJoinWait) {
					Error("join_timeout");
					code = 3;
					break;
				}
			} else if (host && !announced) {
				announced = true;
				Event("hosting").Num("loopback", a_options.loopback).Str("name", name).Send();
			}
			if (tunnel.Finished()) {
				code = 3;
				break;
			}
		}
		tunnel.Stop(Clock::now());
		for (const auto until = Clock::now() + kGoodbye; Clock::now() < until;) {
			tunnel.Pump(Clock::now());
			::Sleep(5);
		}
		Event("stopped").Num("code", code).Send();
		return code;
	}
}

int main(int a_argc, char** a_argv)
{
	::SetConsoleOutputCP(CP_UTF8);
	Options options;
	if (!Parse(a_argc, a_argv, options)) {
		std::fputs(kUsage, stderr);
		return 1;
	}
	if (options.mode == "probe") {
		return Probe();
	}
	HANDLE parent = nullptr;
	if (options.parent) {
		parent = ::OpenProcess(SYNCHRONIZE, FALSE, options.parent);
		if (!parent) {
			Error("parent_gone");
			return 2;
		}
	}
	::SetConsoleCtrlHandler(OnConsole, TRUE);
	if (options.stdinControl) {
		// The Minecraft side asks us to stop this way, so we can say goodbye; its end closes stdin too.
		std::thread([] {
			char line[256];
			while (std::fgets(line, sizeof(line), stdin)) {
				if (std::string_view(line).starts_with("stop")) {
					break;
				}
			}
			g_stop = true;
		}).detach();
	}
	::timeBeginPeriod(1);  // 1 ms waits: Steam's messages are polled
	int code = 2;
	try {
		net::Winsock winsock;
		code = Run(options, parent);
	} catch (const std::exception& e) {
		Error("internal", e.what());
	}
	::timeEndPeriod(1);
	if (parent) {
		::CloseHandle(parent);
	}
	return code;
}
