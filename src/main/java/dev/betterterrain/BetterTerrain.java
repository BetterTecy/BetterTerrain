package dev.betterterrain;

import dev.betterterrain.worldgen.landscape.MountainHeight;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BetterTerrain implements ModInitializer {
    public static final String MOD_ID = "betterterrain";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE,
                Identifier.fromNamespaceAndPath(MOD_ID, "mountain_height"), MountainHeight.CODEC);
        LOGGER.info("BetterTerrain initialized");
    }
}
