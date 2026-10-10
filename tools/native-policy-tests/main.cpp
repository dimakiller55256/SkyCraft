#include "CombatPolicy.h"
#include "ItemCheckpoint.h"
#include <iostream>
#include <limits>
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
    std::cout<<"PASS: item recovery accepts acknowledged removal and refuses save/journal rollback, interrupted removal and duplicate IDs\n";
    std::cout<<"PASS: native hit monotonic freshness, expired/invalid events and shield front/back\n";
}
