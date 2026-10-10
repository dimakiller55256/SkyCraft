#pragma once
#include <string>
#include <vector>
#include <unordered_map>
namespace skycraft::ItemCheckpoint {
    enum class Phase { Prepared, Removing, Removed, Done, Cancelled };
    struct Record { std::string id; Phase phase; };
    inline bool Removed(Phase phase){return phase==Phase::Removed||phase==Phase::Done;}
    // Both directions matter: neither a rolled-back game save nor a rolled-back external
    // journal can authorize a second removal. Any interrupted removal is ambiguous.
    inline bool CanResume(const std::vector<Record>& checkpoint,const std::vector<Record>& journal) {
        std::unordered_map<std::string,Phase> saved,current;
        for(const auto& r:checkpoint)if(r.id.empty()||!saved.emplace(r.id,r.phase).second||r.phase==Phase::Removing)return false;
        for(const auto& r:journal)if(r.id.empty()||!current.emplace(r.id,r.phase).second||r.phase==Phase::Removing)return false;
        for(const auto& [id,phase]:current)if(Removed(phase)&&(!saved.contains(id)||!Removed(saved.at(id))))return false;
        for(const auto& [id,phase]:saved)if(Removed(phase)&&(!current.contains(id)||!Removed(current.at(id))))return false;
        return true;
    }
}
