#pragma once
#include <cstdint>
#include <string_view>

namespace skycraft::ItemForm {
    // TESDataHandler::LookupForm<T> checks one exact T::FORMTYPE. Bound objects
    // are a base class shared by MISC/WEAP/ARMO/BOOK, not a concrete form type.
    // Resolve plugin/local identity first, then use the inheritance-aware cast.
    template <class Bound, class Handler>
    Bound* Resolve(Handler* handler, std::uint32_t local, std::string_view plugin) {
        if (!handler) return nullptr;
        auto* form = handler->LookupForm(local, plugin);
        return form ? form->template As<Bound>() : nullptr;
    }
}
