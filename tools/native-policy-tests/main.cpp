#include "CombatPolicy.h"
#include "CoopPolicy.h"
#include "ItemCheckpoint.h"
#include "ItemForm.h"
#include "KeyReconcile.h"
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
    {
        using Keys=skycraft::KeyReconcile<8>;
        using A=Keys::Action;
        constexpr std::size_t W=3;
        Keys k;
        // Skyrim's event and the physical key agree: nothing to replay.
        k.Sent(W,true);
        if(k.Poll(W,true,true)!=A::kNone||!k.Down(W))return 16;
        // A release Skyrim's queue dropped is replayed on the second poll, not the first.
        if(k.Poll(W,false,true)!=A::kNone||k.Poll(W,false,true)!=A::kRelease||k.Down(W))return 17;
        // A dropped press likewise, when presses may be replayed.
        if(k.Poll(W,true,true)!=A::kNone||k.Poll(W,true,true)!=A::kPress||!k.Down(W))return 18;
        // A late event (one-poll difference) never produces a replay.
        k.Sent(W,true);
        if(k.Poll(W,false,true)!=A::kNone||k.Poll(W,true,true)!=A::kNone||k.Poll(W,false,true)!=A::kNone)return 19;
        k.Sent(W,false);
        if(k.Poll(W,true,true)!=A::kNone)return 20;
        k.Sent(W,true);
        if(k.Poll(W,true,true)!=A::kNone)return 21;
        // With a Minecraft screen up presses are never replayed, releases still are.
        k.Clear();
        if(k.Poll(W,true,false)!=A::kNone||k.Poll(W,true,false)!=A::kNone||k.Down(W))return 22;
        k.Sent(W,true);
        if(k.Poll(W,false,false)!=A::kNone||k.Poll(W,false,false)!=A::kRelease)return 23;
        // A half-counted press is forgotten when presses stop being allowed.
        if(k.Poll(W,true,true)!=A::kNone||k.Poll(W,false,false)!=A::kNone||k.Poll(W,true,true)!=A::kNone)return 24;
        if(k.Poll(99,true,true)!=A::kNone||k.Down(99))return 25;
    }
    {
        using namespace skycraft::CoopPolicy;
        // STR's remote player: runtime reference of a runtime NPC, essential, in PlayerFaction.
        const ActorFacts remote{false,0xFF000812u,0xFF000811u,true,true};
        if(!LooksLikeStrRemotePlayer(remote))return 26;
        ActorFacts f=remote;f.isPlayer=true;if(LooksLikeStrRemotePlayer(f))return 27;          // the local player
        f=remote;f.refId=0x0001A694u;if(LooksLikeStrRemotePlayer(f))return 28;                // a placed NPC
        f=remote;f.baseId=0x00013BBDu;if(LooksLikeStrRemotePlayer(f))return 29;               // a summoned or random-event NPC of a real base
        f=remote;f.inPlayerFaction=false;if(LooksLikeStrRemotePlayer(f))return 30;            // a runtime follower
        f=remote;f.essential=false;if(LooksLikeStrRemotePlayer(f))return 31;
        // Hidden only near a Minecraft body; once hidden, kept hidden until clearly elsewhere.
        if(!HideRemotePlayer(false,3.0)||HideRemotePlayer(false,5.0)||!HideRemotePlayer(true,7.0)||HideRemotePlayer(true,9.0))return 32;
        if(HideRemotePlayer(false,std::numeric_limits<double>::infinity())||HideRemotePlayer(true,std::numeric_limits<double>::quiet_NaN()))return 33;
    }
    std::cout<<"PASS: plugin/local item resolution accepts MISC/WEAP/ARMO/BOOK base objects; exact-type regression reproduced; unknown/wrong/non-bound sources rejected\n";
    std::cout<<"PASS: item recovery accepts acknowledged removal and refuses save/journal rollback, interrupted removal and duplicate IDs\n";
    std::cout<<"PASS: native hit monotonic freshness, expired/invalid events and shield front/back\n";
    std::cout<<"PASS: lost key presses/releases replayed after two polls, late events ignored, no press replay under a Minecraft screen\n";
    std::cout<<"PASS: STR remote players told apart from the player, NPCs and followers; hidden only near a Minecraft body, with hysteresis\n";
}
