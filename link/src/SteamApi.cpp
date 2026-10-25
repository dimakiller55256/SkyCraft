#include "SteamApi.h"

#include <wincrypt.h>
#include <wintrust.h>
#include <softpub.h>

#include <algorithm>
#include <cwchar>
#include <filesystem>
#include <fstream>
#include <initializer_list>
#include <iterator>
#include <set>
#include <string_view>
#include <type_traits>

#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "crypt32.lib")
#pragma comment(lib, "wintrust.lib")

namespace link::steam
{
	namespace
	{
		namespace fs = std::filesystem;

		constexpr std::size_t kMaxCandidates = 16;
		constexpr int         kMaxDepth = 4;  // common\<game>\bin\win64\steam_api64.dll is at depth 3

		std::wstring Wide(std::string_view a_utf8)
		{
			if (a_utf8.empty()) {
				return {};
			}
			const int    n = ::MultiByteToWideChar(CP_UTF8, 0, a_utf8.data(), static_cast<int>(a_utf8.size()), nullptr, 0);
			std::wstring out(static_cast<std::size_t>(n), L'\0');
			::MultiByteToWideChar(CP_UTF8, 0, a_utf8.data(), static_cast<int>(a_utf8.size()), out.data(), n);
			return out;
		}

		std::wstring Key(const fs::path& a_path)
		{
			std::error_code ec;
			auto            key = fs::weakly_canonical(a_path, ec).wstring();
			if (ec) {
				key = a_path.wstring();
			}
			std::ranges::transform(key, key.begin(), [](wchar_t a_c) { return static_cast<wchar_t>(::towlower(a_c)); });
			return key;
		}

		std::wstring RegistryString(HKEY a_root, const wchar_t* a_key, const wchar_t* a_value)
		{
			wchar_t buffer[MAX_PATH * 2]{};
			DWORD   size = sizeof(buffer);
			if (::RegGetValueW(a_root, a_key, a_value, RRF_RT_REG_SZ, nullptr, buffer, &size) != ERROR_SUCCESS) {
				return {};
			}
			return buffer;
		}

		// Steam's own folder and the libraries libraryfolders.vdf lists: "path" "D:\\SteamLibrary",
		// or "1" "D:\\SteamLibrary" in the format before 2021.
		std::vector<fs::path> Libraries(const fs::path& a_steam)
		{
			std::vector<fs::path> out{ fs::path(a_steam).make_preferred() };  // the registry has "d:/steam"
			std::ifstream         file(a_steam / L"steamapps" / L"libraryfolders.vdf", std::ios::binary);
			std::string           line;
			auto                  digits = [](const std::string& a_s) { return !a_s.empty() && std::ranges::all_of(a_s, [](char a_c) { return a_c >= '0' && a_c <= '9'; }); };
			while (std::getline(file, line)) {
				std::vector<std::string> tokens;
				for (std::size_t at = 0; (at = line.find('"', at)) != std::string::npos;) {
					std::string token;
					std::size_t i = at + 1;
					for (; i < line.size() && line[i] != '"'; ++i) {
						if (line[i] == '\\' && i + 1 < line.size()) {
							++i;
						}
						token.push_back(line[i]);
					}
					tokens.push_back(std::move(token));
					at = i + 1;
				}
				if (tokens.size() == 2 && (tokens[0] == "path" || (digits(tokens[0]) && tokens[1].find(':') != std::string::npos))) {
					out.push_back(fs::path(Wide(tokens[1])).make_preferred());
				}
			}
			return out;
		}

		// Every export SkyCraft Link uses; a DLL from an SDK too old to have them all is skipped.
		bool Bind(HMODULE a_dll, Api& a_api, std::string& a_missing)
		{
			auto find = [&](auto& a_fn, std::initializer_list<const char*> a_names) {
				for (const char* name : a_names) {
					if (const auto p = ::GetProcAddress(a_dll, name)) {
						a_fn = reinterpret_cast<std::remove_reference_t<decltype(a_fn)>>(p);
						return true;
					}
				}
				return false;
			};
			auto need = [&](auto& a_fn, std::initializer_list<const char*> a_names) {
				if (find(a_fn, a_names)) {
					return true;
				}
				if (a_missing.empty()) {
					a_missing = *a_names.begin();
				}
				return false;
			};

			bool ok = true;
			find(a_api.InitFlat, { "SteamAPI_InitFlat" });
			find(a_api.Init, { "SteamAPI_Init" });
			if (!a_api.InitFlat && !a_api.Init) {
				a_missing = "SteamAPI_InitFlat";
				ok = false;
			}
			ok &= need(a_api.Shutdown, { "SteamAPI_Shutdown" });
			ok &= need(a_api.IsSteamRunning, { "SteamAPI_IsSteamRunning" });
			ok &= need(a_api.GetHSteamPipe, { "SteamAPI_GetHSteamPipe" });
			ok &= need(a_api.ManualDispatch_Init, { "SteamAPI_ManualDispatch_Init" });
			ok &= need(a_api.ManualDispatch_RunFrame, { "SteamAPI_ManualDispatch_RunFrame" });
			ok &= need(a_api.ManualDispatch_GetNextCallback, { "SteamAPI_ManualDispatch_GetNextCallback" });
			ok &= need(a_api.ManualDispatch_FreeLastCallback, { "SteamAPI_ManualDispatch_FreeLastCallback" });
			ok &= need(a_api.ManualDispatch_GetAPICallResult, { "SteamAPI_ManualDispatch_GetAPICallResult" });

			ok &= need(a_api.SteamMatchmaking, { "SteamAPI_SteamMatchmaking_v009" });
			ok &= need(a_api.SteamFriends, { "SteamAPI_SteamFriends_v018", "SteamAPI_SteamFriends_v017" });  // v018: SDK 1.60+
			ok &= need(a_api.SteamUser, { "SteamAPI_SteamUser_v023", "SteamAPI_SteamUser_v022", "SteamAPI_SteamUser_v021", "SteamAPI_SteamUser_v020" });
			ok &= need(a_api.SteamNetworkingMessages, { "SteamAPI_SteamNetworkingMessages_SteamAPI_v002" });
			ok &= need(a_api.SteamNetworkingUtils, { "SteamAPI_SteamNetworkingUtils_SteamAPI_v004", "SteamAPI_SteamNetworkingUtils_SteamAPI_v003" });

			ok &= need(a_api.CreateLobby, { "SteamAPI_ISteamMatchmaking_CreateLobby" });
			ok &= need(a_api.JoinLobby, { "SteamAPI_ISteamMatchmaking_JoinLobby" });
			ok &= need(a_api.LeaveLobby, { "SteamAPI_ISteamMatchmaking_LeaveLobby" });
			ok &= need(a_api.SetLobbyData, { "SteamAPI_ISteamMatchmaking_SetLobbyData" });
			ok &= need(a_api.GetLobbyData, { "SteamAPI_ISteamMatchmaking_GetLobbyData" });
			ok &= need(a_api.GetLobbyOwner, { "SteamAPI_ISteamMatchmaking_GetLobbyOwner" });
			ok &= need(a_api.GetNumLobbyMembers, { "SteamAPI_ISteamMatchmaking_GetNumLobbyMembers" });
			ok &= need(a_api.GetLobbyMemberByIndex, { "SteamAPI_ISteamMatchmaking_GetLobbyMemberByIndex" });
			ok &= need(a_api.InviteUserToLobby, { "SteamAPI_ISteamMatchmaking_InviteUserToLobby" });
			ok &= need(a_api.RequestLobbyData, { "SteamAPI_ISteamMatchmaking_RequestLobbyData" });

			ok &= need(a_api.GetFriendCount, { "SteamAPI_ISteamFriends_GetFriendCount" });
			ok &= need(a_api.GetFriendByIndex, { "SteamAPI_ISteamFriends_GetFriendByIndex" });
			ok &= need(a_api.GetFriendPersonaName, { "SteamAPI_ISteamFriends_GetFriendPersonaName" });
			ok &= need(a_api.GetFriendGamePlayed, { "SteamAPI_ISteamFriends_GetFriendGamePlayed" });
			ok &= need(a_api.SetRichPresence, { "SteamAPI_ISteamFriends_SetRichPresence" });
			ok &= need(a_api.GetPersonaName, { "SteamAPI_ISteamFriends_GetPersonaName" });

			ok &= need(a_api.GetSteamID, { "SteamAPI_ISteamUser_GetSteamID" });
			ok &= need(a_api.BLoggedOn, { "SteamAPI_ISteamUser_BLoggedOn" });

			ok &= need(a_api.SendMessageToUser, { "SteamAPI_ISteamNetworkingMessages_SendMessageToUser" });
			ok &= need(a_api.ReceiveMessagesOnChannel, { "SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel" });
			ok &= need(a_api.AcceptSessionWithUser, { "SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser" });
			ok &= need(a_api.CloseSessionWithUser, { "SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser" });
			ok &= need(a_api.ReleaseMessage, { "SteamAPI_SteamNetworkingMessage_t_Release" });
			ok &= need(a_api.SetGlobalConfigValueInt32, { "SteamAPI_ISteamNetworkingUtils_SetGlobalConfigValueInt32" });
			ok &= need(a_api.InitRelayNetworkAccess, { "SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess" });
			ok &= need(a_api.SetSteamID64, { "SteamAPI_SteamNetworkingIdentity_SetSteamID64" });
			ok &= need(a_api.GetSteamID64, { "SteamAPI_SteamNetworkingIdentity_GetSteamID64" });
			return ok;
		}
	}

	std::string Utf8(const std::wstring& a_text)
	{
		if (a_text.empty()) {
			return {};
		}
		const int   n = ::WideCharToMultiByte(CP_UTF8, 0, a_text.data(), static_cast<int>(a_text.size()), nullptr, 0, nullptr, nullptr);
		std::string out(static_cast<std::size_t>(n), '\0');
		::WideCharToMultiByte(CP_UTF8, 0, a_text.data(), static_cast<int>(a_text.size()), out.data(), n, nullptr, nullptr);
		return out;
	}

	std::vector<std::wstring> Candidates()
	{
		std::vector<std::wstring> out;
		std::set<std::wstring>    files;
		auto                      add = [&](const fs::path& a_file) {
			std::error_code ec;
			if (out.size() < kMaxCandidates && fs::is_regular_file(a_file, ec) && files.insert(Key(a_file)).second) {
				out.push_back(a_file.wstring());
			}
		};

		wchar_t self[MAX_PATH * 2]{};
		if (const DWORD n = ::GetModuleFileNameW(nullptr, self, static_cast<DWORD>(std::size(self))); n > 0 && n < std::size(self)) {
			add(fs::path(self).parent_path() / L"steam_api64.dll");
		}

		std::set<std::wstring> scanned;
		for (const auto& steam : { RegistryString(HKEY_CURRENT_USER, L"Software\\Valve\\Steam", L"SteamPath"),
				 RegistryString(HKEY_LOCAL_MACHINE, L"SOFTWARE\\WOW6432Node\\Valve\\Steam", L"InstallPath") }) {
			if (steam.empty()) {
				continue;
			}
			for (const auto& library : Libraries(steam)) {
				if (!scanned.insert(Key(library)).second) {
					continue;
				}
				std::error_code                     ec;
				fs::recursive_directory_iterator    it(library / L"steamapps" / L"common", fs::directory_options::skip_permission_denied, ec);
				const fs::recursive_directory_iterator end;
				for (; !ec && it != end && out.size() < kMaxCandidates; it.increment(ec)) {
					if (it.depth() >= kMaxDepth) {
						it.disable_recursion_pending();
					}
					if (it->is_regular_file(ec) && ::_wcsicmp(it->path().filename().c_str(), L"steam_api64.dll") == 0) {
						add(it->path());
					}
				}
			}
		}
		return out;
	}

	bool SignedByValve(const std::wstring& a_path)
	{
		WINTRUST_FILE_INFO file{};
		file.cbStruct = sizeof(file);
		file.pcwszFilePath = a_path.c_str();

		WINTRUST_DATA data{};
		data.cbStruct = sizeof(data);
		data.dwUIChoice = WTD_UI_NONE;
		data.fdwRevocationChecks = WTD_REVOKE_NONE;
		data.dwUnionChoice = WTD_CHOICE_FILE;
		data.pFile = &file;
		data.dwStateAction = WTD_STATEACTION_VERIFY;
		data.dwProvFlags = WTD_CACHE_ONLY_URL_RETRIEVAL;  // offline: no network lookups

		GUID action = WINTRUST_ACTION_GENERIC_VERIFY_V2;
		bool valve = false;
		if (::WinVerifyTrust(static_cast<HWND>(INVALID_HANDLE_VALUE), &action, &data) == ERROR_SUCCESS) {
			if (auto* provider = ::WTHelperProvDataFromStateData(data.hWVTStateData)) {
				if (auto* signer = ::WTHelperGetProvSignerFromChain(provider, 0, FALSE, 0); signer && signer->csCertChain > 0) {
					wchar_t name[256]{};
					::CertGetNameStringW(signer->pasCertChain[0].pCert, CERT_NAME_SIMPLE_DISPLAY_TYPE, 0, nullptr, name, static_cast<DWORD>(std::size(name)));
					valve = std::wstring_view(name).starts_with(L"Valve Corp");  // "Valve Corp." / "Valve Corporation"
				}
			}
		}
		data.dwStateAction = WTD_STATEACTION_CLOSE;
		::WinVerifyTrust(static_cast<HWND>(INVALID_HANDLE_VALUE), &action, &data);
		return valve;
	}

	bool Api::Load(const std::vector<std::wstring>& a_candidates, std::vector<std::string>& a_report)
	{
		for (const auto& candidate : a_candidates) {
			if (!SignedByValve(candidate)) {
				a_report.push_back(Utf8(candidate) + ": not signed by Valve, skipped");
				continue;
			}
			const HMODULE library = ::LoadLibraryExW(candidate.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
			if (!library) {
				a_report.push_back(Utf8(candidate) + ": can't load it (error " + std::to_string(::GetLastError()) + ")");
				continue;
			}
			Api         api;
			std::string missing;
			if (!Bind(library, api, missing)) {
				::FreeLibrary(library);
				a_report.push_back(Utf8(candidate) + ": unusable, it has no " + missing);
				continue;
			}
			api.dll = library;
			api.path = candidate;
			*this = api;
			return true;
		}
		return false;
	}

	// Call Shutdown first: Steam's threads must be gone before the DLL is.
	void Api::Unload()
	{
		if (dll) {
			::FreeLibrary(dll);
		}
		*this = Api{};
	}
}
