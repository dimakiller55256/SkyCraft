#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace skycraft
{
	// What Minecraft was last told about each key, checked against the physical key once a frame.
	// Skyrim's input queue holds at most 10 button events a frame (BSInputEventQueue::
	// MAX_BUTTON_EVENTS) and drops the rest, presses and releases alike; every held key takes one of
	// those slots each frame. A difference lasting kFrames polls is such a lost event. A shorter one
	// is only Skyrim's event arriving after the poll (input is polled before the player update).
	template <std::size_t N>
	class KeyReconcile
	{
	public:
		enum class Action : std::uint8_t
		{
			kNone,
			kPress,
			kRelease,
		};

		static constexpr std::uint8_t kFrames = 2;

		// An event for this key reached Minecraft.
		void Sent(std::size_t a_key, bool a_down)
		{
			if (a_key < N) {
				sent_[a_key] = a_down;
				frames_[a_key] = 0;
			}
		}

		[[nodiscard]] bool Down(std::size_t a_key) const { return a_key < N && sent_[a_key]; }

		// Minecraft let go of everything (it was told to, or dropped input on its own).
		void Clear()
		{
			sent_.fill(false);
			frames_.fill(0);
		}

		// a_pressAllowed: a lost press may be replayed. Not while a Minecraft screen takes typing,
		// where a replayed key would act twice; a lost release is always replayed.
		Action Poll(std::size_t a_key, bool a_physicalDown, bool a_pressAllowed)
		{
			if (a_key >= N) {
				return Action::kNone;
			}
			const bool lost = a_physicalDown != sent_[a_key] && (sent_[a_key] || a_pressAllowed);
			if (!lost) {
				frames_[a_key] = 0;
				return Action::kNone;
			}
			if (++frames_[a_key] < kFrames) {
				return Action::kNone;
			}
			Sent(a_key, a_physicalDown);
			return a_physicalDown ? Action::kPress : Action::kRelease;
		}

	private:
		std::array<bool, N>         sent_{};
		std::array<std::uint8_t, N> frames_{};
	};
}
