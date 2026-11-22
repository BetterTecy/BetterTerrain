package dev.betterterrain.worldgen.landscape;

import dev.betterterrain.worldgen.landscape.LongMountainRange.Bend;
import dev.betterterrain.worldgen.landscape.LongMountainRange.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** 在任意世界坐标采样按种子布置的长山脉。 */
public final class MountainRegion {
    public static final int CELL_SIZE = 8_192;
    private static final int RANGE_REACH = 3_840;
    private static final long LAYOUT_SALT = 0x7BD4A54E3CA18EB9L;
    private static final int CACHE_SIZE = 128;
    private static final Bend[] BENDS = Bend.values();

    private final long seed;
    private final int patternOffset;
    private final double directionOffset;
    // 布置是种子和坐标的纯函数。缓存冲突只影响重算次数。
    private final AtomicReferenceArray<Layout> cache = new AtomicReferenceArray<>(CACHE_SIZE);

    public MountainRegion(long seed) {
        this.seed = seed;
        var random = new SplittableRandom(seed ^ LAYOUT_SALT);
        patternOffset = random.nextInt(4);
        directionOffset = random.nextDouble(-0.16, 0.16);
    }

    /** 统计半开区间内按中心归属的山脉，同一主脊可以穿过窗口边缘。 */
    public List<Band> bandsInWindow(int x0, int z0, int size) {
        if (size <= 0) throw new IllegalArgumentException("size 必须为正方块数");
        List<Band> bands = new ArrayList<>();
        double x1 = (double) x0 + size;
        double z1 = (double) z0 + size;
        for (int z = cell(z0); z <= cell(z1 - 1); z++) {
            for (int x = cell(x0); x <= cell(x1 - 1); x++) {
                Layout layout = layout(x, z);
                addIfInside(bands, layout.first(), x0, z0, x1, z1);
                addIfInside(bands, layout.second(), x0, z0, x1, z1);
            }
        }
        return List.copyOf(bands);
    }

    private static void addIfInside(List<Band> bands, Band band, int x0, int z0, double x1, double z1) {
        if (band.centerX() >= x0 && band.centerX() < x1 && band.centerZ() >= z0 && band.centerZ() < z1) {
            bands.add(band);
        }
    }

    /** 查相邻布点区的山脉影响，交叠处保留较高地势并平滑两侧坡面。 */
    public double height(double x, double z, double continentalness, double baseHeight) {
        if (continentalness <= 0.0) return baseHeight;
        double highest = baseHeight;
        double other = baseHeight;
        int maxX = cell(x + RANGE_REACH);
        int maxZ = cell(z + RANGE_REACH);
        for (int cz = cell(z - RANGE_REACH); cz <= maxZ; cz++) {
            for (int cx = cell(x - RANGE_REACH); cx <= maxX; cx++) {
                Layout layout = layout(cx, cz);
                double first = bandHeight(layout.first(), x, z, continentalness, baseHeight);
                double second = bandHeight(layout.second(), x, z, continentalness, baseHeight);
                double localHigh = Math.max(first, second);
                double localOther = Math.min(first, second);
                if (layout.junction() != null) {
                    double joint = layout.junction().height(x, z, continentalness, baseHeight);
                    localOther = Math.max(localOther, Math.min(localHigh, joint));
                    localHigh = Math.max(localHigh, joint);
                }
                other = Math.max(other, Math.max(localOther, Math.min(highest, localHigh)));
                highest = Math.max(highest, localHigh);
            }
        }
        double width = Math.min(12.0, other - baseHeight);
        double difference = highest - other;
        if (difference >= width) return highest;
        // 连续接合两侧斜率，高程始终受较高表面约束。
        double overlap = width - difference;
        return highest - overlap * overlap / (4.0 * width);
    }

    private static double bandHeight(Band band, double x, double z, double land, double base) {
        if (Math.abs(x - band.centerX()) > RANGE_REACH || Math.abs(z - band.centerZ()) > RANGE_REACH) return base;
        return band.range().sample(x, z, land, base).height();
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL_SIZE);
    }

    private Layout layout(int x, int z) {
        long key = mix(((long) x << 32) | Integer.toUnsignedLong(z));
        int index = (int) key & (CACHE_SIZE - 1);
        Layout existing = cache.get(index);
        if (existing != null && existing.x() == x && existing.z() == z) return existing;
        Layout generated = createLayout(x, z, new SplittableRandom(seed ^ key ^ LAYOUT_SALT));
        cache.set(index, generated);
        return generated;
    }

    private Layout createLayout(int x, int z, SplittableRandom random) {
        int quadrant = ((x & 1) + 2 * (z & 1) + patternOffset) & 3;
        Relation relation = switch (quadrant) {
            case 0 -> Relation.VALLEY;
            case 3 -> Relation.JUNCTION;
            default -> Relation.FOOTHILLS;
        };
        double direction = quadrant * Math.PI / 4.0 + directionOffset + random.nextDouble(-0.18, 0.18);
        int pivotX = Math.toIntExact((long) x * CELL_SIZE + CELL_SIZE / 2 + random.nextInt(-1_050, 1_051));
        int pivotZ = Math.toIntExact((long) z * CELL_SIZE + CELL_SIZE / 2 + random.nextInt(-1_050, 1_051));
        int tall = quadrant % 2 == 0 ? random.nextInt(2) : -1;
        Band first;
        Band second;
        Junction junction = null;
        if (relation == Relation.JUNCTION) {
            double turn = random.nextDouble(1.35, 1.90) * (random.nextBoolean() ? 1 : -1);
            first = band(random, x, z, 0, quadrant, relation, pivotX, pivotZ, direction, 2_380, 0, tall == 0);
            second = band(random, x, z, 1, quadrant, relation, pivotX, pivotZ, direction + turn, 2_380, 0, tall == 1);
            junction = new Junction(first.join(),
                    first.range().position(0.18, 0), second.range().position(0.18, 0));
        } else {
            boolean valley = relation == Relation.VALLEY;
            double across = valley ? random.nextDouble(1_750, 1_970) : random.nextDouble(420, 485);
            double spread = valley ? 0.11 : 0.05;
            double firstDirection = direction + random.nextDouble(-spread, spread);
            double secondDirection = direction + random.nextDouble(-spread, spread);
            first = band(random, x, z, 0, quadrant, relation, pivotX, pivotZ, firstDirection,
                    random.nextDouble(-980, -440), -across, tall == 0);
            second = band(random, x, z, 1, quadrant, relation, pivotX, pivotZ, secondDirection,
                    random.nextDouble(440, 980), across, tall == 1);
        }
        return new Layout(x, z, first, second, junction);
    }

    private static Band band(SplittableRandom random, int x, int z, int slot, int group, Relation relation,
                             int pivotX, int pivotZ, double direction, double along, double across, boolean tall) {
        int centerX = Math.toIntExact(Math.round(pivotX + along * StrictMath.cos(direction) - across * StrictMath.sin(direction)));
        int centerZ = Math.toIntExact(Math.round(pivotZ + along * StrictMath.sin(direction) + across * StrictMath.cos(direction)));
        double[] heights = {random.nextDouble(310, 367), random.nextDouble(310, 367)};
        if (tall) heights[random.nextInt(heights.length)] = random.nextDouble(392, 408);
        Bend bend = BENDS[(group + slot + 1) % BENDS.length];
        var range = new LongMountainRange(random.nextLong(), centerX, centerZ, direction, bend, heights);
        Position join = relation == Relation.JUNCTION ? new Position(pivotX, pivotZ) : null;
        return new Band(new BandId(x, z, slot), range, heights.length, centerX, centerZ, bend, relation, group, join);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    public enum Relation { VALLEY, FOOTHILLS, JUNCTION }

    public record BandId(int cellX, int cellZ, int slot) {}

    public record Band(BandId id, LongMountainRange range, int peakCount, int centerX, int centerZ,
                       Bend bend, Relation relation, int directionGroup, Position join) {}

    private record Layout(int x, int z, Band first, Band second, Junction junction) {}

    private record Junction(Position anchor, Position first, Position second) {
        double height(double x, double z, double land, double base) {
            if (Math.abs(x - anchor.x()) > 1_600 || Math.abs(z - anchor.z()) > 1_600) return base;
            double distance = Math.min(distance(x, z, first), distance(x, z, second));
            double width = StrictMath.clamp(1.0 - distance / 460.0, 0.0, 1.0);
            double inland = StrictMath.clamp(land / 0.3, 0.0, 1.0);
            return base + 110.0 * width * width * (3.0 - 2.0 * width) * inland * inland * (3.0 - 2.0 * inland);
        }

        private double distance(double x, double z, Position end) {
            double dx = end.x() - anchor.x();
            double dz = end.z() - anchor.z();
            double offsetX = x - anchor.x();
            double offsetZ = z - anchor.z();
            double progress = StrictMath.clamp((offsetX * dx + offsetZ * dz) / (dx * dx + dz * dz), 0.0, 1.0);
            return Math.sqrt((offsetX - dx * progress) * (offsetX - dx * progress)
                    + (offsetZ - dz * progress) * (offsetZ - dz * progress));
        }
    }
}
