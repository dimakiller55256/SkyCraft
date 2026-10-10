#pragma once
namespace skycraft::Items {
    void RegisterSerialization();
    void Install();
    void BeforeLoad();
    void OnGameLoaded();
    void PerFrame(RE::PlayerCharacter* player);
}
