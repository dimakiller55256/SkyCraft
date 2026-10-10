#include "CombatPolicy.h"
#include <iostream>
#include <limits>
int main() {
    using namespace skycraft::CombatPolicy;
    using namespace std::chrono_literals;
    auto at=Clock::time_point{}+1s;
    if(!Recent(at,at+100ms) || Recent(at,at+351ms) || Recent({},at) || Recent(at,at-1ms))return 1;
    if(!InShieldFront(0,0,1) || InShieldFront(0,0,-1) || !InShieldFront(90,-1,0) || InShieldFront(90,1,0))return 2;
    if(InShieldFront(0,0,0) || InShieldFront(std::numeric_limits<double>::quiet_NaN(),1,1))return 3;
    std::cout<<"PASS: native hit monotonic freshness, expired/invalid events and shield front/back\n";
}
