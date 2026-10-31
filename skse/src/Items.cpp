#include "Items.h"
#include "ItemDefaults.h"
#include "ItemCheckpoint.h"
#include "ItemForm.h"
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
    bool testProfile=false, fallback=true;
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
    // Anything the table doesn't name still becomes something fitting, so nothing a player picks
    // up (mods' items too) stays behind. Keys and generated forms have no stable identity or use.
    const char* FallbackItem(RE::TESForm* form) {
        using T=RE::FormType;
        using S=RE::BGSBipedObjectForm::BipedObjectSlot;
        switch(form->GetFormType()) {
        case T::Ammo: return "minecraft:arrow";
        case T::Book: return "minecraft:book";
        case T::Scroll: return "minecraft:paper";
        case T::Ingredient: return "minecraft:wheat_seeds";
        case T::SoulGem: return "minecraft:amethyst_shard";
        case T::Light: return "minecraft:torch";
        case T::Misc: return "minecraft:iron_nugget";
        case T::AlchemyItem: {
            auto* potion=form->As<RE::AlchemyItem>();
            return potion->IsFood()?"minecraft:bread":potion->IsPoison()?"minecraft:spider_eye":"minecraft:honey_bottle";
        }
        case T::Weapon: {
            auto* weapon=form->As<RE::TESObjectWEAP>();
            if(weapon->IsBow()||weapon->IsCrossbow())return "minecraft:bow";
            if(weapon->IsStaff())return "minecraft:stick";
            return weapon->IsOneHandedAxe()||weapon->IsTwoHandedAxe()?"minecraft:iron_axe":"minecraft:iron_sword";
        }
        case T::Armor: {
            auto* armor=form->As<RE::TESObjectARMO>();
            if(armor->IsShield())return "minecraft:shield";
            if(armor->IsClothing()||armor->HasPartOf(S::kRing)||armor->HasPartOf(S::kAmulet))return "minecraft:gold_nugget";
            const bool heavy=armor->IsHeavyArmor();
            if(armor->HasPartOf(S::kBody))return heavy?"minecraft:iron_chestplate":"minecraft:leather_chestplate";
            if(armor->HasPartOf(S::kHead)||armor->HasPartOf(S::kHair))return heavy?"minecraft:iron_helmet":"minecraft:leather_helmet";
            if(armor->HasPartOf(S::kFeet))return heavy?"minecraft:iron_boots":"minecraft:leather_boots";
            return "minecraft:leather";
        }
        default: return nullptr;
        }
    }
    bool Convertible(RE::FormID id) {
        if(table.contains(id))return true;
        if(!fallback)return false;
        auto* form=RE::TESForm::LookupByID(id);auto* file=form?form->GetFile(0):nullptr;const char* item=form&&file?FallbackItem(form):nullptr;
        if(!item)return false;
        table.emplace(id,Mapping{std::string(file->GetFilename()),item,form->GetLocalFormID(),1});
        logger::info("item conversion fallback: {} {:08X} -> {}",form->GetName(),id,item);
        return true;
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
    const char* ProtectionReason(RE::InventoryEntryData* entry) {
        if (!entry || !entry->object) return "inventory_entry_missing";
        if (entry->IsQuestObject()) return "quest_item";
        if (entry->IsWorn()) return "equipped";
        if (entry->IsFavorited()) return "favorited";
        if (entry->IsPoisoned()) return "poisoned";
        auto* enchantable = entry->object->As<RE::TESEnchantableForm>();
        if (enchantable && enchantable->formEnchanting) return "base_enchantment";
        if (entry->extraLists) for (auto* extra : *entry->extraLists) {
            if (!extra) continue;
            if (extra->HasType<RE::ExtraEnchantment>()) return "instance_enchantment";
            if (extra->HasType<RE::ExtraHealth>()) return "tempered";
            if (extra->HasType<RE::ExtraTextDisplayData>()) return "custom_name";
            if (extra->HasType<RE::ExtraSoul>()) return "instance_soul";
        }
        return nullptr;
    }
    void Cancel(Json& tx, const char* reason, RE::FormID resolved=0, int available=0) {
        tx["phase"]="cancelled";tx["reason"]=reason;
        tx["resolvedForm"]=resolved;tx["available"]=available;
        logger::warn("item conversion cancelled: {} reason {} {}:{:06X} resolved {:08X} available {} requested {}; no conversion removal performed",
            tx.at("id").get<std::string>(),reason,tx.at("plugin").get<std::string>(),tx.at("local").get<std::uint32_t>(),resolved,available,tx.at("count").get<int>());
        if (!Persist()) Pause("cannot persist cancellation; Skyrim inventory unchanged");
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
            try { auto data=Json::parse(bytes);character=data.at("character");if(!std::regex_match(character,std::regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))throw std::runtime_error("invalid Skyrim item profile identity");loaded=data.at("transactions");loadedReady=true;testProfile=data.value("test",false); }
            catch (const std::exception& e) { Pause(e.what()); }
        }
    }
    class PickupSink final : public RE::BSTEventSink<RE::TESContainerChangedEvent> {
        RE::BSEventNotifyControl ProcessEvent(const RE::TESContainerChangedEvent* event, RE::BSTEventSource<RE::TESContainerChangedEvent>*) override {
            std::scoped_lock guard(lock);
            auto* player=RE::PlayerCharacter::GetSingleton();
            if (event && player && !removing && !blocked && ready && event->newContainer==player->GetFormID() && event->itemCount>0 && Convertible(event->baseObj)) {
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
        fallback=data.value("fallback",true);
        auto* forms=RE::TESDataHandler::GetSingleton();
        std::unordered_set<std::string> keys;
        for(const auto& row:data.at("items")) {
            Mapping m{row.at("plugin"),row.at("minecraft"),static_cast<std::uint32_t>(std::stoul(row.at("form").get<std::string>(),nullptr,16)),row.value("count",1)};
            if(m.plugin.size()>127||m.local>0xFFFFFF||m.item.size()>95||m.ratio<1||m.ratio>64||!std::regex_match(m.item,std::regex("[a-z0-9_.-]+:[a-z0-9_./-]+(#[a-z0-9_./:=,-]+)?")))throw std::runtime_error("invalid item table row");
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
        // Conversion works from the first moment, with no save of the player's: a save without our
        // record is a fresh profile, and loading an older save just leaves Minecraft's items alone.
        blocked=false;
        if(character.empty()){character=UUID();history=Json::array();loaded=Json::array();}
        history=loaded;
        const auto path=journalDir/(character+".json");
        if(std::filesystem::exists(path)){std::ifstream in(path);Json j;in>>j;history=j.at("transactions");}
        if(!ItemCheckpoint::CanResume(Checkpoint(loaded),Checkpoint(history)))logger::info("item conversion: this save is older than the journal; items already in Minecraft stay there");
        for(auto& tx:history) {
            const auto phase=tx.at("phase").get<std::string>();
            if(phase=="prepared"||phase=="removing"){tx.at("phase")="cancelled";tx["reason"]="interrupted";logger::info("item conversion cancelled: {} reason interrupted",tx.at("id").get<std::string>());}
        }
        if(!Persist()){Pause("cannot persist resumed item journal");return;}
        ready=true;logger::info("item conversion resumed: {} transactions {}",character,history.size());
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
    if(!ready)ready=true;  // a new game, before any load finished: nothing to wait for
    if(!Link::Get().McAlive()||State().skyrimMenuOpen)return;
    try {
        auto* tx=Pending();
        if(!tx&&!pickups.empty()) {
            auto [formID,count]=pickups.front();pickups.pop_front();const auto& map=table.at(formID);
            auto* object=RE::TESForm::LookupByID<RE::TESBoundObject>(formID);if(!object)return;
            auto inventory=player->GetInventory();auto found=inventory.find(object);
            const char* protection=found==inventory.end()?"inventory_entry_missing":ProtectionReason(found->second.second.get());
            if(protection){logger::info("item conversion protected pickup retained: {:08X} reason {}",formID,protection);return;}
            const int incoming=std::min(count,found->second.first);count=std::min(incoming,4096/map.ratio);if(count<1)return;
            if(incoming>count)pickups.emplace_front(formID,incoming-count);
            if(history.size()>=4096){history.erase(history.begin(),history.begin()+2048);loaded=history;}  // Minecraft keeps its own receipts
            history.push_back(Json{{"id",UUID()},{"plugin",map.plugin},{"local",map.local},{"item",map.item},{"count",count},{"output",count*map.ratio},{"phase","prepared"},{"target",""}});
            if(!Persist()){history.erase(history.size()-1);Pause("cannot persist preparation; Skyrim inventory unchanged");return;}
            tx=&history.back();lastSend=0;
        }
        if(!tx){Link::Get().WriteItemRequest({});return;}
        proto::ItemReply reply{};
        if(Link::Get().ReadItemReply(reply)&&std::string(reply.id,strnlen_s(reply.id,40))==tx->at("id").get<std::string>()) {
            const std::string phase=tx->at("phase");
            if(reply.status==1&&phase=="prepared") {
                tx->at("target")=std::string(reply.target,strnlen_s(reply.target,40));
                auto* object=ItemForm::Resolve<RE::TESBoundObject>(RE::TESDataHandler::GetSingleton(),tx->at("local").get<std::uint32_t>(),tx->at("plugin").get<std::string>());
                if(!object){Cancel(*tx,"source_form_missing_or_not_bound");return;}
                auto inventory=player->GetInventory();auto found=inventory.find(object);const int count=tx->at("count");
                if(found==inventory.end()){Cancel(*tx,"inventory_entry_missing",object->GetFormID());return;}
                if(found->second.first<count){Cancel(*tx,"inventory_count_changed",object->GetFormID(),found->second.first);return;}
                if(const char* protection=ProtectionReason(found->second.second.get())){Cancel(*tx,protection,object->GetFormID(),found->second.first);return;}
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
            if(reply.status==3){if(phase=="removed"){tx->at("phase")="cancelled";(*tx)["reason"]="minecraft_rejected_after_removal";Persist();logger::error("item conversion lost one transaction: {}",reply.message);}else{(*tx)["detail"]=std::string(reply.message,strnlen_s(reply.message,sizeof(reply.message)));Cancel(*tx,"minecraft_rejected");logger::warn("item conversion rejected; retained in Skyrim: {}",reply.message);}return;}
            if(reply.status==2&&phase=="prepared"){Pause("Minecraft already delivered before known Skyrim removal");return;}
        }
        if(GetTickCount64()-lastSend>=1000)Send(*tx);
    } catch(const std::exception& e) {removing=false;Pause(e.what());}
}
}
