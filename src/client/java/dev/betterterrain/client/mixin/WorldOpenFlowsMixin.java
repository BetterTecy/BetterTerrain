package dev.betterterrain.client.mixin;

import dev.betterterrain.client.TerrainPreviewLaunch;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
    @Shadow
    private void openWorldLoadBundledResourcePack(LevelStorageAccess access, WorldStem stem,
                                                  PackRepository packs, Runnable canceled) {
        throw new AssertionError();
    }

    // 脚本已明确选用自定义维度，仅为校验通过的预览存档接续加载。
    @Inject(method = "openWorldCheckWorldStemCompatibility", at = @At("HEAD"), cancellable = true)
    private void betterterrain$reopenPreview(LevelStorageAccess access, WorldStem stem,
                                             PackRepository packs, Runnable canceled, CallbackInfo callback) {
        if (TerrainPreviewLaunch.isPreviewReload(access.getLevelId(), stem.worldDataAndGenSettings().genSettings())) {
            openWorldLoadBundledResourcePack(access, stem, packs, canceled);
            callback.cancel();
        }
    }
}
