#pragma once
#include <chrono>
#include <cmath>

namespace skycraft::CombatPolicy
{
	using Clock = std::chrono::steady_clock;
	inline bool Recent(Clock::time_point event, Clock::time_point now)
	{
		return event != Clock::time_point{} && now >= event && now - event <= std::chrono::milliseconds(350);
	}
	inline bool InShieldFront(double yaw, double dx, double dz)
	{
		if (!std::isfinite(yaw) || !std::isfinite(dx) || !std::isfinite(dz) || std::hypot(dx, dz) < 1e-6) return false;
		const double angle = yaw * 3.14159265358979323846 / 180.0;
		return -std::sin(angle) * dx + std::cos(angle) * dz > 0.0;
	}
}
