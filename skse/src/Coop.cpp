#include "Game.h"
#include "CoopPolicy.h"

namespace skycraft
{
	namespace
	{
		constexpr RE::FormID kPlayerFaction = 0x00000DB1;

		bool strChecked = false;
		bool strLoaded = false;
		// Remote players this frame hid (their friend's Minecraft body stands there instead).
		std::unordered_map<RE::FormID, bool> hiddenPlayers;
		proto::WorldEntities                 minecraftEntities{};
		std::vector<McVec>                   minecraftPlayers;

		double NearestMinecraftPlayer(const McVec& a_at)
		{
			double best = std::numeric_limits<double>::infinity();
			for (const auto& p : minecraftPlayers) {
				const double dx = p.x - a_at.x, dy = p.y - a_at.y, dz = p.z - a_at.z;
				best = std::min(best, std::sqrt(dx * dx + dy * dy * 0.25 + dz * dz));  // standing on a ledge or a step still counts
			}
			return best;
		}
	}

	namespace Coop
	{
		bool StrLoaded()
		{
			if (!strChecked) {
				strChecked = true;
				// STR's launcher maps SkyrimSE.exe into its own process (Code/immersive_launcher).
				wchar_t path[MAX_PATH]{};
				const DWORD length = ::GetModuleFileNameW(nullptr, path, MAX_PATH);
				const std::wstring_view exe(path, length);
				const auto slash = exe.find_last_of(L"\\/");
				const std::wstring name(slash == std::wstring_view::npos ? exe : exe.substr(slash + 1));
				strLoaded = _wcsicmp(name.c_str(), L"SkyrimTogether.exe") == 0;
				logger::info("Skyrim Together Reborn: {}", strLoaded ? "loaded (SkyrimTogether.exe); co-op rules on" : "not loaded");
			}
			return strLoaded;
		}

		bool IsStrRemotePlayer(RE::Actor* a_actor)
		{
			if (!a_actor || !StrLoaded()) {
				return false;
			}
			static auto* playerFaction = RE::TESForm::LookupByID<RE::TESFaction>(kPlayerFaction);
			auto*        base = a_actor->GetActorBase();
			return CoopPolicy::LooksLikeStrRemotePlayer({ a_actor->IsPlayerRef(), a_actor->GetFormID(), base ? base->GetFormID() : 0u,
				playerFaction && a_actor->IsInFaction(playerFaction), a_actor->IsEssential() });
		}

		void PerFrame(RE::PlayerCharacter* a_player, bool a_minecraftInWorld)
		{
			if (!StrLoaded()) {
				return;
			}
			minecraftPlayers.clear();
			if (a_minecraftInWorld && Link::Get().ReadWorldEntities(minecraftEntities)) {
				for (std::uint32_t i = 0; i < minecraftEntities.count; ++i) {
					const auto& e = minecraftEntities.entities[i];
					if (e.kind == proto::kWeRemotePlayer) {
						minecraftPlayers.push_back({ e.x, e.y, e.z });
					}
				}
			}
			auto* lists = RE::ProcessLists::GetSingleton();
			if (!lists) {
				return;
			}
			for (auto& handle : lists->highActorHandles) {
				auto  actorPtr = handle.get();
				auto* actor = actorPtr.get();
				if (!actor || actor == a_player || !IsStrRemotePlayer(actor)) {
					continue;
				}
				auto&      hidden = hiddenPlayers[actor->GetFormID()];
				const bool hide = CoopPolicy::HideRemotePlayer(hidden, NearestMinecraftPlayer(SkyToMc(actor->GetPosition())));
				if (hide != hidden) {
					logger::info("STR player {:08X} ({}): {}", actor->GetFormID(), actor->GetDisplayFullName(),
						hide ? "hidden, their Minecraft body is there" : "shown, no Minecraft body near it");
					hidden = hide;
				}
				// Every frame: STR rebuilds a remote player's 3D when their equipment or appearance changes.
				if (auto* root = actor->Get3D(); root && root->GetAppCulled() != hide) {
					root->SetAppCulled(hide);
				}
			}
			if (hiddenPlayers.size() > 64) {
				hiddenPlayers.clear();  // players come and go; the next frame finds the current ones again
			}
		}
	}
}
