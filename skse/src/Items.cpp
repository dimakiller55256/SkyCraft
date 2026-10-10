#include "Items.h"
#include "ItemDefaults.h"
#include "ItemCheckpoint.h"
#include "Game.h"
#include "Link.h"
#include <nlohmann/json.hpp>
#include <filesystem>
#include <fstream>
#include <regex>
#include <objbase.h>

namespace skycraft::Items {
namespace {
    using Json = nlohmann::json;
    constexpr std::uint32_t recordType = 0x5349544D; // SITM
    struct Mapping { std::string plugin, item; std::uint32_t local; int ratio; };
    std::unordered_map<RE::FormID, Mapping> table;
    std::deque<std::pair<RE::FormID, int>> pickups;
    Json history = Json::array(), loaded = Json::array();
    std::string character;
    std::string lastTestControl;
    bool testProfile=false;
    bool loadedReady = false, ready = false, blocked = false, removing = false, announced = false;
    std::filesystem::path journalDir;
    ULONGLONG lastSend = 0;
    std::recursive_mutex lock;

    std::string UUID() {
        GUID id{}; if (FAILED(CoCreateGuid(&id))) throw std::runtime_error("CoCreateGuid failed");
        char out[40]; std::snprintf(out, sizeof(out), "%08lx-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
            id.Data1, id.Data2, id.Data3, id.Data4[0], id.Data4[1], id.Data4[2], id.Data4[3], id.Data4[4], id.Data4[5], id.Data4[6], id.Data4[7]);
        return out;
    }
    bool AtomicWrite(const std::filesystem::path& path, const std::string& bytes) {
        const auto temp = path.wstring() + L".tmp";
        HANDLE file = CreateFileW(temp.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) return false;
        DWORD written = 0;
        const bool ok = WriteFile(file, bytes.data(), static_cast<DWORD>(bytes.size()), &written, nullptr) && written == bytes.size() && FlushFileBuffers(file);
        CloseHandle(file);
        return ok && MoveFileExW(temp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    }
    bool Persist() {
        try {
            std::filesystem::create_directories(journalDir);
            return AtomicWrite(journalDir / (character + ".json"), Json{{"schema",1},{"character",character},{"test",testProfile},{"transactions",history}}.dump(2));
        } catch (const std::exception& e) { logger::error("item conversion journal: {}", e.what()); return false; }
    }
    void Pause(const char* reason) {
        blocked = true;
        proto::ItemRequest notice{};notice.action=3;notice.count=2;Link::Get().WriteItemRequest(notice);
        logger::error("item conversion paused: {} (character {})", reason, character);
    }
    bool Protected(RE::InventoryEntryData* entry) {
        if (!entry || !entry->object || entry->IsQuestObject() || entry->IsWorn() || entry->IsFavorited() || entry->IsPoisoned() || entry->GetOwner()) return true;
        auto* enchantable = entry->object->As<RE::TESEnchantableForm>();
        if (enchantable && enchantable->formEnchanting) return true;
        if (entry->extraLists) for (auto* extra : *entry->extraLists) {
            if (extra && (extra->HasType<RE::ExtraEnchantment>() || extra->HasType<RE::ExtraHealth>() || extra->HasType<RE::ExtraTextDisplayData>() || extra->HasType<RE::ExtraSoul>())) return true;
        }
        return false;
    }
    void Fill(char* dest, std::size_t cap, const std::string& text) { std::memcpy(dest, text.data(), std::min(cap-1, text.size())); }
    void Send(const Json& tx) {
        proto::ItemRequest req{};
        req.action = tx["phase"] == "removed" ? 2 : 1;
        req.count = tx.at("output").get<std::uint32_t>(); req.localForm = tx.at("local").get<std::uint32_t>();
        Fill(req.id,40,tx.at("id")); Fill(req.character,40,character); Fill(req.target,40,tx.value("target",std::string{}));
        Fill(req.plugin,128,tx.at("plugin")); Fill(req.item,96,tx.at("item"));
        Link::Get().WriteItemRequest(req); lastSend = GetTickCount64();
    }
    Json* Pending() { for (auto& tx : history) if (tx["phase"] == "prepared" || tx["phase"] == "removed") return &tx; return nullptr; }
    std::vector<ItemCheckpoint::Record> Checkpoint(const Json& data) {
        std::vector<ItemCheckpoint::Record> out;
        for(const auto& tx:data) {
            const auto phase=tx.at("phase").get<std::string>();ItemCheckpoint::Phase value;
            if(phase=="prepared")value=ItemCheckpoint::Phase::Prepared;else if(phase=="removing")value=ItemCheckpoint::Phase::Removing;
            else if(phase=="removed")value=ItemCheckpoint::Phase::Removed;else if(phase=="done")value=ItemCheckpoint::Phase::Done;
            else if(phase=="cancelled")value=ItemCheckpoint::Phase::Cancelled;else throw std::runtime_error("invalid item journal phase");
            out.push_back({tx.at("id").get<std::string>(),value});
        }
        return out;
    }
    void Save(SKSE::SerializationInterface* stream) {
        std::scoped_lock guard(lock);
        try {
            if (character.empty()) character = UUID();
            const auto& checkpoint = blocked ? loaded : history;
            const auto bytes = Json{{"schema",1},{"character",character},{"test",testProfile},{"quarantined",blocked},{"transactions",checkpoint}}.dump();
            if (stream->WriteRecord(recordType, 1, bytes.data(), static_cast<std::uint32_t>(bytes.size()))) {
                if (!blocked) { ready = true; loaded = history; }
                logger::info("item conversion Skyrim checkpoint: {} transactions {} blocked {}", character, checkpoint.size(), blocked);
            } else Pause("SKSE could not write inventory checkpoint");
        } catch (const std::exception& e) { Pause(e.what()); }
    }
    void Revert(SKSE::SerializationInterface*) {
        std::scoped_lock guard(lock);
        character.clear(); history=Json::array();loaded=Json::array();pickups.clear();ready=false;loadedReady=false;blocked=false;announced=false;testProfile=false;
    }
    void Load(SKSE::SerializationInterface* stream) {
        std::scoped_lock guard(lock);
        std::uint32_t type, version, length;
        while (stream->GetNextRecordInfo(type,version,length)) {
            if (type != recordType) continue;
            if (version != 1 || length > 8*1024*1024) { Pause("unsupported or oversized Skyrim item checkpoint"); continue; }
            std::string bytes(length,'\0');
            if (stream->ReadRecordData(bytes.data(), length) != length) { Pause("truncated Skyrim item checkpoint"); continue; }
            try { auto data=Json::parse(bytes);character=data.at("character");if(!std::regex_match(character,std::regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))throw std::runtime_error("invalid Skyrim item profile identity");loaded=data.at("transactions");loadedReady=true;testProfile=data.value("test",false);blocked=blocked||data.value("quarantined",false); }
            catch (const std::exception& e) { Pause(e.what()); }
        }
    }
    class PickupSink final : public RE::BSTEventSink<RE::TESContainerChangedEvent> {
        RE::BSEventNotifyControl ProcessEvent(const RE::TESContainerChangedEvent* event, RE::BSTEventSource<RE::TESContainerChangedEvent>*) override {
            std::scoped_lock guard(lock);
            auto* player=RE::PlayerCharacter::GetSingleton();
            if (event && player && !removing && !blocked && ready && event->newContainer==player->GetFormID() && event->itemCount>0 && table.contains(event->baseObj)) {
                if (pickups.size()<1024) pickups.emplace_back(event->baseObj,event->itemCount);
                else logger::warn("item conversion pickup queue full: item remains in Skyrim");
            }
            return RE::BSEventNotifyControl::kContinue;
        }
    } sink;
}
void RegisterSerialization() {
    auto* stream=SKSE::GetSerializationInterface();
    stream->SetUniqueID(recordType);stream->SetSaveCallback(Save);stream->SetLoadCallback(Load);stream->SetRevertCallback(Revert);
}
void Install() {
    std::scoped_lock guard(lock);
    try {
        auto logs=SKSE::log::log_directory();if(!logs)throw std::runtime_error("SKSE log directory unavailable");journalDir=*logs/"SkyCraft-items";
        HMODULE module=nullptr;
        GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,reinterpret_cast<LPCWSTR>(&Install),&module);
        wchar_t name[32768]{};GetModuleFileNameW(module,name,32768);
        auto config=std::filesystem::path(name).parent_path()/"SkyCraft-items.json";
        std::string defaults;for(auto part:kDefaultItemTableParts)defaults+=part;
        Json data;
        if(std::filesystem::exists(config)){std::ifstream input(config);input>>data;}
        else {data=Json::parse(defaults);if(!AtomicWrite(config,defaults))logger::warn("item table could not be created at {}",config.string());}
        if(!data.value("enabled",true)){logger::info("item conversion disabled in table");return;}
        auto* forms=RE::TESDataHandler::GetSingleton();
        std::unordered_set<std::string> keys;
        for(const auto& row:data.at("items")) {
            Mapping m{row.at("plugin"),row.at("minecraft"),static_cast<std::uint32_t>(std::stoul(row.at("form").get<std::string>(),nullptr,16)),row.value("count",1)};
            if(m.plugin.size()>127||m.local>0xFFFFFF||m.item.size()>95||m.ratio<1||m.ratio>64||!std::regex_match(m.item,std::regex("[a-z0-9_.-]+:[a-z0-9_./-]+(#[a-z0-9_]+)?")))throw std::runtime_error("invalid item table row");
            auto key=m.plugin+":"+std::to_string(m.local);if(!keys.insert(key).second)throw std::runtime_error("duplicate item table key");
            if(auto* form=forms->LookupForm(m.local,m.plugin);form&&form->As<RE::TESBoundObject>())table.emplace(form->GetFormID(),m);
        }
        RE::ScriptEventSourceHolder::GetSingleton()->AddEventSink(&sink);
        logger::info("item conversion table: {} resolved records; {}",table.size(),config.string());
    } catch(const std::exception& e) { table.clear();Pause(e.what()); }
}
void BeforeLoad() { std::scoped_lock guard(lock);ready=false;pickups.clear();Link::Get().WriteItemRequest({}); }
void OnGameLoaded() {
    std::scoped_lock guard(lock);pickups.clear();announced=false;lastSend=0;
    try {
        if(blocked){Pause("this Skyrim checkpoint is quarantined; load the last consistent checkpoint or collect a report");return;}
        if(character.empty()) {
            // A save predating the first checkpoint cannot be safely distinguished from a new
            // character after conversions. Require recovery instead of minting another identity.
            if(std::filesystem::exists(journalDir))for(const auto& file:std::filesystem::directory_iterator(journalDir)) {
                if(file.path().extension()==".json") {std::ifstream in(file.path());Json j;in>>j;const auto records=Checkpoint(j.at("transactions"));if(!j.value("test",false)&&std::any_of(records.begin(),records.end(),[](const auto& r){return r.phase==ItemCheckpoint::Phase::Removing||ItemCheckpoint::Removed(r.phase);})){Pause("save has no item checkpoint but previous conversions exist; load a checkpointed save");return;}}
            }
            character=UUID();history=Json::array();ready=false;Persist();return;
        }
        history=loaded;
        const auto path=journalDir/(character+".json");
        if(std::filesystem::exists(path)){std::ifstream in(path);Json j;in>>j;history=j.at("transactions");if(j.value("test",false)!=testProfile){Pause("item journal profile type disagrees with Skyrim checkpoint");return;}}
        if(!ItemCheckpoint::CanResume(Checkpoint(loaded),Checkpoint(history))){Pause("Skyrim checkpoint and external journal disagree, or removal was interrupted; manual recovery required");return;}
        for(auto& tx:history)if(tx.at("phase")=="prepared"&&std::none_of(loaded.begin(),loaded.end(),[&](const auto& old){return old.at("id")==tx.at("id");}))tx.at("phase")="cancelled";
        if(!Persist()){Pause("cannot persist resumed item journal");return;}
        ready=loadedReady;logger::info("item conversion resumed: {} transactions {}",character,history.size());
    } catch(const std::exception& e) {Pause(e.what());}
}
void PerFrame(RE::PlayerCharacter* player) {
    std::scoped_lock guard(lock);
    proto::ItemReply control{};
    if(Link::Get().McAlive()&&Link::Get().ItemTestWorld()&&Link::Get().ReadItemReply(control)&&control.localControl==1) {
        const std::string id(control.id,strnlen_s(control.id,40));
        if(!id.empty()&&id!=lastTestControl) {
            lastTestControl=id;
            if(auto* pending=Pending();pending&&pending->at("phase")=="removed"){Pause("cannot fork item test profile during an unfinished removal");return;}
            try {character=UUID();testProfile=true;history=Json::array();loaded=Json::array();pickups.clear();ready=false;loadedReady=false;blocked=false;announced=false;lastSend=0;if(!Persist()){Pause("cannot create isolated item test profile");return;}logger::info("item conversion test profile created: {}",character);}
            catch(const std::exception& e){Pause(e.what());return;}
        }
    }
    if(testProfile!=Link::Get().ItemTestWorld()){proto::ItemRequest notice{};notice.action=3;notice.count=testProfile?4:5;Link::Get().WriteItemRequest(notice);pickups.clear();return;}
    if(blocked||table.empty()||!player||player->IsDead()||!State().mcInWorld)return;
    if(!ready) {
        if(!announced){logger::info("item conversion: save Skyrim with F5 to initialize a checkpoint");announced=true;}
        proto::ItemRequest notice{};notice.action=3;notice.count=1;if(testProfile)Fill(notice.id,40,lastTestControl);Link::Get().WriteItemRequest(notice);return;
    }
    if(!Link::Get().McAlive()||State().skyrimMenuOpen)return;
    try {
        auto* tx=Pending();
        if(!tx&&!pickups.empty()) {
            auto [formID,count]=pickups.front();pickups.pop_front();const auto& map=table.at(formID);
            auto* object=RE::TESForm::LookupByID<RE::TESBoundObject>(formID);if(!object)return;
            auto inventory=player->GetInventory();auto found=inventory.find(object);
            if(found==inventory.end()||Protected(found->second.second.get())){logger::info("item conversion protected pickup retained: {:08X}",formID);return;}
            const int incoming=std::min(count,found->second.first);count=std::min(incoming,4096/map.ratio);if(count<1)return;
            if(incoming>count)pickups.emplace_front(formID,incoming-count);
            if(history.size()>=8192){Pause("item journal reached its safe size limit");return;}
            history.push_back(Json{{"id",UUID()},{"plugin",map.plugin},{"local",map.local},{"item",map.item},{"count",count},{"output",count*map.ratio},{"phase","prepared"},{"target",""}});
            if(!Persist()){history.erase(history.size()-1);Pause("cannot persist preparation; Skyrim inventory unchanged");return;}
            tx=&history.back();lastSend=0;
        }
        if(!tx){proto::ItemRequest notice{};if(history!=loaded){notice.action=3;notice.count=3;}Link::Get().WriteItemRequest(notice);return;}
        proto::ItemReply reply{};
        if(Link::Get().ReadItemReply(reply)&&std::string(reply.id,strnlen_s(reply.id,40))==tx->at("id").get<std::string>()) {
            const std::string phase=tx->at("phase");
            if(reply.status==1&&phase=="prepared") {
                tx->at("target")=std::string(reply.target,strnlen_s(reply.target,40));
                auto* object=RE::TESDataHandler::GetSingleton()->LookupForm<RE::TESBoundObject>(tx->at("local").get<std::uint32_t>(),tx->at("plugin").get<std::string>());
                if(!object){tx->at("phase")="cancelled";Persist();return;}
                auto inventory=player->GetInventory();auto found=inventory.find(object);const int count=tx->at("count");
                if(found==inventory.end()||found->second.first<count||Protected(found->second.second.get())){tx->at("phase")="cancelled";Persist();return;}
                const int before=found->second.first;tx->at("phase")="removing";
                if(!Persist()){Pause("cannot record removal intent; inventory unchanged");return;}
                removing=true;player->RemoveItem(object,count,RE::ITEM_REMOVE_REASON::kRemove,nullptr,nullptr);removing=false;
                auto after=player->GetInventoryCounts();const int remaining=after.contains(object)?after.at(object):0;
                if(before-remaining!=count){Pause("Skyrim removal count ambiguous; no Minecraft grant sent");return;}
                tx->at("phase")="removed";if(!Persist()){Pause("removal journal could not be saved; manual recovery required");return;}
                logger::info("item conversion removed: {} {}:{:06X} x{} -> {} x{}",tx->at("id").get<std::string>(),tx->at("plugin").get<std::string>(),tx->at("local").get<std::uint32_t>(),count,tx->at("item").get<std::string>(),tx->at("output").get<int>());
                Send(*tx);return;
            }
            if(reply.status==2&&phase=="removed") {
                if(tx->value("target",std::string{})!=std::string(reply.target,strnlen_s(reply.target,40))){Pause("delivery acknowledged by different Minecraft owner/world");return;}
                tx->at("phase")="done";if(!Persist()){Pause("delivery receipt journal write failed");return;}
                logger::info("item conversion delivered: {}",tx->at("id").get<std::string>());Link::Get().WriteItemRequest({});return;
            }
            if(reply.status==3){if(phase=="removed")Pause("Minecraft rejected a removed item; inspect pending journal");else{tx->at("phase")="cancelled";Persist();logger::warn("item conversion rejected; retained in Skyrim: {}",reply.message);}return;}
            if(reply.status==2&&phase=="prepared"){Pause("Minecraft already delivered before known Skyrim removal");return;}
        }
        if(GetTickCount64()-lastSend>=1000)Send(*tx);
    } catch(const std::exception& e) {removing=false;Pause(e.what());}
}
}
