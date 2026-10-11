#include "CombatPolicy.h"
#include "ItemCheckpoint.h"
#include "ItemForm.h"
#include <iostream>
#include <limits>
#include <type_traits>

namespace {
    enum class FormType { None, Misc, Weapon, Armor, Book, Quest };
    struct Bound;
    struct Form {
        static constexpr auto FORMTYPE=FormType::None;
        FormType type;
        template <class T> T* As();
    };
    struct Bound:Form {};
    template <class T> T* Form::As() {
        static_assert(std::is_same_v<T,Bound>);
        return type==FormType::Misc||type==FormType::Weapon||type==FormType::Armor||type==FormType::Book?static_cast<T*>(this):nullptr;
    }
    struct Handler {
        Form* form;
        Form* LookupForm(std::uint32_t local,std::string_view plugin) {
            return local==0x5ACE4&&plugin=="Skyrim.esm"?form:nullptr;
        }
        template <class T> T* LookupForm(std::uint32_t local,std::string_view plugin) {
            auto* found=LookupForm(local,plugin);
            return found&&found->type==T::FORMTYPE?static_cast<T*>(found):nullptr;
        }
    };
}
int main() {
    using namespace skycraft::CombatPolicy;
    using namespace std::chrono_literals;
    auto at=Clock::time_point{}+1s;
    if(!Recent(at,at+100ms) || Recent(at,at+351ms) || Recent({},at) || Recent(at,at-1ms))return 1;
    if(!InShieldFront(0,0,1) || InShieldFront(0,0,-1) || !InShieldFront(90,-1,0) || InShieldFront(90,1,0))return 2;
    if(InShieldFront(0,0,0) || InShieldFront(std::numeric_limits<double>::quiet_NaN(),1,1))return 3;
    using namespace skycraft::ItemCheckpoint;
    if(!CanResume({{"a",Phase::Removed}},{{"a",Phase::Done}}))return 4;
    if(CanResume({},{{"a",Phase::Done}})||CanResume({{"a",Phase::Done}},{}))return 5;
    if(CanResume({{"a",Phase::Prepared}},{{"a",Phase::Removed}}))return 6;
    if(CanResume({{"a",Phase::Removed}},{{"a",Phase::Prepared}}))return 7;
    if(CanResume({{"a",Phase::Removing}},{{"a",Phase::Removing}}))return 8;
    if(CanResume({{"a",Phase::Done}},{{"a",Phase::Done},{"a",Phase::Done}}))return 9;
    if(!CanResume({},{{"a",Phase::Prepared},{"b",Phase::Cancelled}}))return 10;
    for(auto type:{FormType::Misc,FormType::Weapon,FormType::Armor,FormType::Book}) {
        Bound bound;bound.type=type;Handler handler{&bound};
        // Reproduce CommonLib's exact-FORMTYPE overload rejecting the base class.
        if(handler.LookupForm<Bound>(0x5ACE4,"Skyrim.esm")!=nullptr)return 11;
        if(skycraft::ItemForm::Resolve<Bound>(&handler,0x5ACE4,"Skyrim.esm")!=&bound)return 12;
        if(skycraft::ItemForm::Resolve<Bound>(&handler,0x5ACE4,"Other.esp"))return 13;
        if(skycraft::ItemForm::Resolve<Bound>(&handler,0xBAD,"Skyrim.esm"))return 14;
    }
    Form quest{FormType::Quest};Handler handler{&quest};
    if(skycraft::ItemForm::Resolve<Bound>(&handler,0x5ACE4,"Skyrim.esm")||skycraft::ItemForm::Resolve<Bound>(static_cast<Handler*>(nullptr),0x5ACE4,"Skyrim.esm"))return 15;
    std::cout<<"PASS: plugin/local item resolution accepts MISC/WEAP/ARMO/BOOK base objects; exact-type regression reproduced; unknown/wrong/non-bound sources rejected\n";
    std::cout<<"PASS: item recovery accepts acknowledged removal and refuses save/journal rollback, interrupted removal and duplicate IDs\n";
    std::cout<<"PASS: native hit monotonic freshness, expired/invalid events and shield front/back\n";
}
