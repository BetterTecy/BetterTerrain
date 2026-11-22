package dev.betterterrain.mixin;

import dev.betterterrain.worldgen.landscape.MountainHeight.SeededContext;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction.CompileContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(RandomState.class)
abstract class RandomStateMixin {
    @Shadow @Final private long seed;

    // 让山脉使用实际世界种子，并把原版噪声调用委托给原来的编译上下文。
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler;<init>"
                    + "(Lnet/minecraft/world/level/levelgen/densityfunction/DensityFunction$CompileContext;)V"))
    private CompileContext betterterrain$mountainSeed(CompileContext vanilla) {
        return new SeededContext(vanilla, seed);
    }
}
