#pragma once
#include <cstdint>

// Game-independent rules for playing alongside Skyrim Together Reborn (STR). Kept apart from the
// engine code so tools/native-policy-tests can check them.
namespace skycraft::CoopPolicy
{
	// What SkyCraft can see of an actor without STR's internals.
	struct ActorFacts
	{
		bool          isPlayer;         // the local PlayerCharacter
		std::uint32_t refId;            // reference FormID
		std::uint32_t baseId;           // base NPC FormID
		bool          inPlayerFaction;  // PlayerFaction (0xDB1)
		bool          essential;
	};

	// STR spawns each remote player as a new reference of a new NPC (both runtime FF IDs), makes it
	// essential and puts it in PlayerFaction (CharacterService::OnCharacterSpawn, SetPlayerRespawnMode).
	// Ordinary NPCs, followers and the player never have all of those at once.
	inline bool LooksLikeStrRemotePlayer(const ActorFacts& a_actor)
	{
		return !a_actor.isPlayer && a_actor.refId >= 0xFF000000u && a_actor.baseId >= 0xFF000000u && a_actor.inPlayerFaction &&
		       a_actor.essential;
	}

	// A remote player is hidden while a Minecraft player stands where it is (that friend runs SkyCraft:
	// their Minecraft body is drawn instead). STR interpolates its copy behind the real position, so
	// once hidden it stays hidden until it is clearly elsewhere. Distances in blocks.
	inline constexpr double kHideWithin = 4.0;
	inline constexpr double kShowBeyond = 8.0;
	inline bool HideRemotePlayer(bool a_hiddenNow, double a_nearestMinecraftPlayer)
	{
		return a_nearestMinecraftPlayer <= (a_hiddenNow ? kShowBeyond : kHideWithin);
	}
}
