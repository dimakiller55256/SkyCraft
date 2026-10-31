#include "Status.h"

#include <cstdio>

namespace link
{
	namespace
	{
		std::function<void(const std::string&)>& Sink()
		{
			static std::function<void(const std::string&)> sink;
			return sink;
		}
	}

	std::string Quote(std::string_view a_text)
	{
		std::string out;
		out.reserve(a_text.size() + 2);
		out.push_back('"');
		for (const char c : a_text) {
			switch (c) {
			case '"':
				out += "\\\"";
				break;
			case '\\':
				out += "\\\\";
				break;
			case '\n':
				out += "\\n";
				break;
			case '\r':
				out += "\\r";
				break;
			case '\t':
				out += "\\t";
				break;
			default:
				if (static_cast<unsigned char>(c) < 0x20) {
					char escaped[8];
					std::snprintf(escaped, sizeof(escaped), "\\u%04x", static_cast<unsigned>(static_cast<unsigned char>(c)));
					out += escaped;
				} else {
					out.push_back(c);
				}
			}
		}
		out.push_back('"');
		return out;
	}

	Event::Event(std::string_view a_name) :
		text_("{\"event\":" + Quote(a_name))
	{}

	Event& Event::Str(std::string_view a_key, std::string_view a_value) { return Json(a_key, Quote(a_value)); }

	Event& Event::Num(std::string_view a_key, std::int64_t a_value) { return Json(a_key, std::to_string(a_value)); }

	Event& Event::Flag(std::string_view a_key, bool a_value) { return Json(a_key, a_value ? "true" : "false"); }

	Event& Event::Json(std::string_view a_key, std::string_view a_json)
	{
		text_ += ',';
		text_ += Quote(a_key);
		text_ += ':';
		text_ += a_json;
		return *this;
	}

	void Event::Send()
	{
		const std::string line = text_ + "}\n";
		if (const auto& sink = Sink()) {
			sink(line);
			return;
		}
		std::fwrite(line.data(), 1, line.size(), stdout);
		std::fflush(stdout);
	}

	void SetEventSink(std::function<void(const std::string&)> a_sink) { Sink() = std::move(a_sink); }
}
