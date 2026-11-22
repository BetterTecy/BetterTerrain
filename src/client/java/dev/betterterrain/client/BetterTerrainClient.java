package dev.betterterrain.client;

import net.fabricmc.api.ClientModInitializer;

public final class BetterTerrainClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TerrainPreviewLaunch.register();
    }
}
