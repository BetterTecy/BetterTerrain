package dev.betterterrain.worldgen.landscape;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class MountainHeight implements DensityFunction {
    private static final MountainHeight INSTANCE = new MountainHeight();
    public static final MapCodec<MountainHeight> CODEC = MapCodec.unit(INSTANCE);
    private static final double LAND = 0.72;
    private static final double BASE_HEIGHT = 118.0;
    private static final Interval HEIGHT_RANGE = Interval.of((float) BASE_HEIGHT, 512.0F);

    private MountainHeight() {}

    public static double height(MountainRegion mountains, double x, double z) {
        return mountains.height(x, z, LAND, BASE_HEIGHT);
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        if (!(context instanceof SeededContext seeded)) {
            throw new IllegalStateException("betterterrain:mountain_height 缺少世界种子");
        }
        return new Sampler(new MountainRegion(seeded.seed()));
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return HEIGHT_RANGE;
    }

    @Override
    public int domainAxes() {
        return AXIS_X | AXIS_Z;
    }

    @Override
    public MapCodec<MountainHeight> codec() {
        return CODEC;
    }

    public record SeededContext(CompileContext vanilla, long seed) implements CompileContext {
        @Override
        public Noise createNoiseSampler(Holder<NormalNoise> parameters) {
            return vanilla.createNoiseSampler(parameters);
        }

        @Override
        public RandomSource createRandom(Identifier salt) {
            return vanilla.createRandom(salt);
        }

        @Override
        @SuppressWarnings("deprecation")
        public RandomSource createEndIslandRandom() {
            return vanilla.createEndIslandRandom();
        }
    }

    private record Sampler(MountainRegion mountains) implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return (float) height(mountains, x, z);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            // 每个水平采样位置只查询一次山脉，高度沿整列复用。
            for (int z = 0; z < volume.sizeZ(); z++) {
                for (int x = 0; x < volume.sizeX(); x++) {
                    float height = sampleValue(context, volume.blockX(x), 0, volume.blockZ(z));
                    int start = volume.indexUnchecked(x, 0, z);
                    buffer.setRange(start, volume.sizeY(), height);
                }
            }
        }
    }
}
