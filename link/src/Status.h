#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <string_view>

// What SkyCraft Link reports: one JSON object per line on stdout ("event" first), read by the
// Minecraft side. Strings are UTF-8; texts meant for the player are in Russian.
namespace link
{
	class Event
	{
	public:
		explicit Event(std::string_view a_name);

		Event& Str(std::string_view a_key, std::string_view a_value);
		Event& Num(std::string_view a_key, std::int64_t a_value);
		Event& Flag(std::string_view a_key, bool a_value);
		Event& Json(std::string_view a_key, std::string_view a_json);  // an already encoded value
		void   Send();

	private:
		std::string text_;
	};

	std::string Quote(std::string_view a_text);
	// Where events go: stdout, unless a test collects them.
	void SetEventSink(std::function<void(const std::string&)> a_sink);
}
