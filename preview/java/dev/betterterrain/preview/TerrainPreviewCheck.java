package dev.betterterrain.preview;

import dev.betterterrain.worldgen.landscape.LongMountainRange;
import dev.betterterrain.worldgen.landscape.LongMountainRange.Position;
import dev.betterterrain.worldgen.landscape.MixedMountainRegion;
import dev.betterterrain.worldgen.landscape.MixedMountainRegion.Feature;
import dev.betterterrain.worldgen.landscape.MixedMountainRegion.Kind;
import dev.betterterrain.worldgen.landscape.MountainRegion;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

final class TerrainPreviewCheck {
    private static final double LAND = TerrainPreview.LAND;
    private static final double BASE = TerrainPreview.BASE_HEIGHT;
    private static final int[][] WINDOWS = {{-512, -512}, {512, -512}, {-512, 512}};

    private TerrainPreviewCheck() {}

    static void run() {
        for (long seed : TerrainPreview.SEEDS) {
            var range = new LongMountainRange(seed, 0, 0);
            checkLandforms(range, seed);
            checkWindowEdges(range, seed);
            checkWorld(seed);
            checkMixedWorld(seed);
        }
    }

    private static void checkMixedWorld(long seed) {
        var region = new MixedMountainRegion(seed);
        var parallel = new MixedMountainRegion(seed);
        int total = 0;
        int windows = 0;
        int half = MixedMountainRegion.TILE_SIZE / 2;
        for (int[] center : TerrainPreview.VIEW_CENTERS) {
            int x = center[0] - half;
            int z = center[1] - half;
            checkMixedWindow(region, seed, x, z);
            for (int dx : new int[] {-3_000, 0, 3_000}) {
                for (int dz : new int[] {-3_000, 0, 3_000}) {
                    total += (int) region.featuresInWindow(x + dx, z + dz, MixedMountainRegion.TILE_SIZE)
                            .stream().filter(feature -> feature.kind() != Kind.MASSIF).count();
                    windows++;
                }
            }
            IntStream.range(0, 256).parallel().forEach(i -> {
                int k = (255 - i) * 67;
                require(region.height(x, z + k) == parallel.height(x, z + k)
                        && region.height(x + k, z) == parallel.height(x + k, z), seed, "混合山地并行确定性");
                require(Math.abs(region.height(x - 1, z + k) - region.height(x, z + k)) <= 4
                        && Math.abs(region.height(x + k, z - 1) - region.height(x + k, z)) <= 4,
                        seed, "混合山地跨区连续");
            });
        }
        double average = total / (double) windows;
        require(average >= 7 && average <= 9, seed, "混合山脉偏移窗口密度");
        System.out.printf(java.util.Locale.ROOT, "种子 %d：混合山脉 %d 个偏移窗口平均 %.2f 条，形态与边界通过%n",
                seed, windows, average);
    }

    private static void checkMixedWindow(MixedMountainRegion region, long seed, int x, int z) {
        var features = region.featuresInWindow(x, z, MixedMountainRegion.TILE_SIZE);
        var counts = new EnumMap<Kind, Integer>(Kind.class);
        var ids = new HashSet<String>();
        int tall = 0;
        for (Feature feature : features) {
            require(ids.add(feature.id()), seed, "混合山地标识唯一");
            counts.merge(feature.kind(), 1, Integer::sum);
            for (Position summit : feature.summits()) {
                double altitude = region.height(summit.x(), summit.z());
                if (altitude > 384) {
                    require(altitude >= 392 - 0.00001 && altitude <= 408, seed, "探云峰高程");
                    tall++;
                }
            }
            switch (feature.kind()) {
                case ARC -> {
                    double angle = Math.abs(Math.toDegrees(feature.main().turn()));
                    require(angle >= 65 && angle <= 110, seed, "弧形山脉连续转向");
                    Position start = feature.main().position(0, 0);
                    Position end = feature.main().position(1, 0);
                    for (double t : new double[]{0.44, 0.50, 0.56}) {
                        double height = region.height(start.x() + t * (end.x() - start.x()),
                                start.z() + t * (end.z() - start.z()));
                        require(height <= BASE + 55, seed, "弧形山脉开口通向低地");
                    }
                }
                case BRANCHED -> {
                    require(feature.connections().size() >= 1 && feature.connections().size() <= 2, seed, "分支数量");
                    for (var branch : feature.connections()) {
                        Position root = branch.position(0, 0);
                        Position middle = branch.position(0.5, 0);
                        Position tip = branch.position(1.0, 0);
                        require(branch.length() >= 1_200 && branch.length() <= 2_400
                                && Math.abs(feature.main().height(root.x(), root.z()) - branch.height(root.x(), root.z())) < 0.01
                                && branch.height(middle.x(), middle.z()) > BASE + 70
                                && Math.abs(branch.height(tip.x(), tip.z()) - BASE) < 0.01, seed, "分支平滑汇接并衰减");
                    }
                }
                case MASSIF -> {
                    var peaks = feature.summits();
                    require(peaks.size() >= 3 && peaks.size() <= 5 && feature.connections().size() == peaks.size() - 2,
                            seed, "块状山地的主峰与短脊数量");
                    for (int i = 0; i < peaks.size(); i++) {
                        Position peak = peaks.get(i);
                        for (int j = 0; j < i; j++) {
                            Position other = peaks.get(j);
                            require(Math.hypot(peak.x() - other.x(), peak.z() - other.z()) >= 512,
                                    seed, "块状山地主峰间距");
                        }
                    }
                }
                default -> { }
            }
        }
        require(counts.equals(java.util.Map.of(Kind.LONG, 4, Kind.ARC, 1, Kind.PARALLEL, 2, Kind.BRANCHED, 1, Kind.MASSIF, 1)),
                seed, "混合全景组成");
        require(tall == 2, seed, "混合全景探云峰名额");
        var pair = features.stream().filter(feature -> feature.kind() == Kind.PARALLEL).toList();
        for (double t : new double[]{0.30, 0.50, 0.70}) {
            Position a = pair.getFirst().main().position(t, 0);
            Position b = pair.getLast().main().position(t, 0);
            double lowRidge = Math.min(region.height(a.x(), a.z()), region.height(b.x(), b.z()));
            double valley = region.height((a.x() + b.x()) / 2, (a.z() + b.z()) / 2);
            require(lowRidge - valley >= 70, seed, "并列山脉纵谷下切");
        }
    }

    private static void checkWorld(long seed) {
        var region = new MountainRegion(seed);
        var repeated = new MountainRegion(seed);
        int total = 0;
        int windows = 0;
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int half = TerrainPreview.OVERVIEW / 2;
        for (int[] center : TerrainPreview.VIEW_CENTERS) {
            checkWindow(region, seed, center[0] - half, center[1] - half);
            for (int dx : new int[] {-3_000, 0, 3_000}) {
                for (int dz : new int[] {-3_000, 0, 3_000}) {
                    int count = region.bandsInWindow(center[0] - half + dx, center[1] - half + dz,
                            TerrainPreview.OVERVIEW).size();
                    total += count;
                    windows++;
                    minimum = Math.min(minimum, count);
                    maximum = Math.max(maximum, count);
                }
            }
            for (int stride : new int[] {64, 16, 4, 1}) {
                IntStream.range(0, 256).parallel().forEach(index -> {
                    int reverse = 255 - index;
                    int x = center[0] - 512 + reverse * stride;
                    int z = center[1] + 4_032 - reverse * stride;
                    require(TerrainPreview.columnHeight(region, x, z, stride)
                            == TerrainPreview.columnHeight(repeated, x, z, stride), seed, "四档并行采样确定性");
                });
            }
        }
        double average = total / (double) windows;
        require(Math.abs(average - 8) <= 1, seed, "偏移观察窗口的平均密度");
        checkPlanningBoundaries(region, seed);
        System.out.printf(java.util.Locale.ROOT,
                "种子 %d：%d 个偏移窗口平均 %.2f 条（%d–%d），跨区、走向、交汇和并行确定性通过%n",
                seed, windows, average, minimum, maximum);
    }

    private static void checkWindow(MountainRegion region, long seed, int x0, int z0) {
        List<MountainRegion.Band> bands = region.bandsInWindow(x0, z0, TerrainPreview.OVERVIEW);
        require(bands.size() == 8, seed, "对齐观察窗口有八条山脉");
        var ids = new HashSet<MountainRegion.BandId>();
        var directions = new HashSet<Integer>();
        var bends = EnumSet.noneOf(LongMountainRange.Bend.class);
        int aboveCloud = 0;
        for (var band : bands) {
            require(ids.add(band.id()), seed, "山脉标识唯一");
            directions.add(band.directionGroup());
            bends.add(band.bend());
            var range = band.range();
            double saddle = sample(range, 0.5, 0.0, LAND).height();
            for (int index = 0; index < band.peakCount(); index++) {
                double progress = LongMountainRange.summitProgress(index, band.peakCount());
                var point = range.position(progress, 0);
                double height = sample(range, progress, 0, LAND).height();
                require(height - saddle >= 40, seed, "主峰与鞍部高差");
                if (height > 384) {
                    double merged = region.height(point.x(), point.z(), LAND, BASE);
                    require(merged >= 392 && merged <= 408, seed, "云上主峰高程");
                    aboveCloud++;
                }
            }
            require(range.length() * 0.68 > 3_072, seed, "各山带连续主脊");
            int distinct = 0;
            for (int index = 16; index <= 84; index++) {
                var ridge = range.position(index / 100.0, 0);
                var sample = range.sample(ridge.x(), ridge.z(), LAND, BASE);
                require(sample.mountainWeight() >= 0.99, seed, "各山带主脊连续");
                if (Math.abs(region.height(ridge.x(), ridge.z(), LAND, BASE) - sample.height()) <= 4) distinct++;
            }
            require(distinct >= 40, seed, "交叠后仍可独立追踪主脊的大部分");
        }
        require(directions.size() == 4 && bends.size() == 3, seed, "走向与弯曲多样性");
        require(aboveCloud == 2, seed, "每个样例窗口的探云峰");
        int valleys = 0;
        int slopes = 0;
        int junctions = 0;
        for (int index = 0; index < bands.size(); index += 2) {
            var a = bands.get(index);
            var b = bands.get(index + 1);
            require(a.id().cellX() == b.id().cellX() && a.id().cellZ() == b.id().cellZ(), seed, "同区关系配对");
            double middleX = (a.centerX() + b.centerX()) / 2.0;
            double middleZ = (a.centerZ() + b.centerZ()) / 2.0;
            switch (a.relation()) {
                case VALLEY -> {
                    require(region.height(middleX, middleZ, LAND, BASE) < BASE + 24, seed, "谷地保持低地");
                    valleys++;
                }
                case FOOTHILLS -> {
                    require(findFootOverlap(a, b, middleX, middleZ), seed, "保留山麓交叠");
                    slopes++;
                }
                case JUNCTION -> {
                    require(a.join().equals(b.join()), seed, "两条主脊共享交汇位置");
                    checkJunctionLeg(region, seed, a);
                    checkJunctionLeg(region, seed, b);
                    junctions++;
                }
            }
        }
        require(valleys == 1 && slopes == 2 && junctions == 1, seed, "谷地、山麓和主脊汇接都有样例");
    }

    private static void checkJunctionLeg(MountainRegion region, long seed, MountainRegion.Band band) {
        var start = band.range().position(0.18, 0);
        var end = band.join();
        for (int step = 0; step <= 16; step++) {
            double fraction = step / 16.0;
            double x = start.x() + (end.x() - start.x()) * fraction;
            double z = start.z() + (end.z() - start.z()) * fraction;
            require(region.height(x, z, LAND, BASE) >= BASE + 24, seed, "汇接方向有连续高地");
        }
    }

    private static boolean findFootOverlap(MountainRegion.Band first, MountainRegion.Band second, double x, double z) {
        for (int dz = -512; dz <= 512; dz += 64) {
            for (int dx = -512; dx <= 512; dx += 64) {
                double a = first.range().sample(x + dx, z + dz, LAND, BASE).mountainWeight();
                double b = second.range().sample(x + dx, z + dz, LAND, BASE).mountainWeight();
                if (a > 0.02 && b > 0.02 && a < 0.6 && b < 0.6) return true;
            }
        }
        return false;
    }

    private static void checkPlanningBoundaries(MountainRegion region, long seed) {
        boolean raisedX = false;
        boolean raisedZ = false;
        int size = MountainRegion.CELL_SIZE;
        for (int cell = -3; cell <= 3; cell++) {
            int boundary = cell * size;
            for (int along = -3 * size; along <= 3 * size; along += 128) {
                double left = region.height(boundary - 0.01, along, LAND, BASE);
                double right = region.height(boundary + 0.01, along, LAND, BASE);
                double north = region.height(along, boundary - 0.01, LAND, BASE);
                double south = region.height(along, boundary + 0.01, LAND, BASE);
                require(Math.abs(left - right) <= 0.2 && Math.abs(north - south) <= 0.2, seed, "布点区边界连续");
                raisedX |= Math.min(left, right) >= BASE + 24;
                raisedZ |= Math.min(north, south) >= BASE + 24;
            }
        }
        require(raisedX && raisedZ, seed, "两个方向都有跨区山体");
    }

    private static void checkLandforms(LongMountainRange range, long seed) {
        require(range.length() >= 4_096 && range.length() <= 8_192, seed, "山脉长度");
        for (double progress : new double[] {LongMountainRange.FIRST_SUMMIT, LongMountainRange.SECOND_SUMMIT}) {
            double summit = sample(range, progress, 0, LAND).height();
            require(summit >= 392 && summit <= 408, seed, "主峰高程");
            require(sample(range, progress, 0, 0).height() == BASE, seed, "海岸与无抬升对照");
            require(sample(range, progress, 0, 0.1).height() < summit, seed, "大陆度渐变");
        }
        require(sample(range, 0.5, 0, LAND).height() < 384, seed, "中段低于云层");
        require(sample(range, 0, 0, LAND).height() <= BASE + 1e-9, seed, "起点收低");
        require(sample(range, 1, 0, LAND).height() <= BASE + 1e-9, seed, "终点收低");
        require(sample(range, 0.5, 1_024, LAND).height() == BASE, seed, "山带边界回归基底");
        require(0.68 * range.length() > 3_072, seed, "连续主脊长度");
        for (int index = 16; index <= 84; index++) {
            require(sample(range, index / 100.0, 0, LAND).mountainWeight() >= 0.99, seed, "主脊连续");
        }
        for (int side : new int[] {-1, 1}) {
            double last = sample(range, LongMountainRange.FIRST_SUMMIT, 0, LAND).height();
            for (int distance = 4; distance <= 1_024; distance += 4) {
                double height = sample(range, LongMountainRange.FIRST_SUMMIT, side * distance, LAND).height();
                require(height <= last + 1e-9, seed, "横剖面向山麓下降");
                last = height;
            }
        }
    }

    private static void checkWindowEdges(LongMountainRange range, long seed) {
        final int side = 257;
        double[][] sampled = new double[WINDOWS.length][side * side];
        for (int index = 0; index < WINDOWS.length; index++) {
            int[] window = WINDOWS[index];
            for (int tick = 0; tick < side * side; tick++) {
                sampled[index][tick] = height(range, window, tick, side);
            }
        }
        for (int index = 0; index < side; index++) {
            require(sampled[0][index * side + side - 1] == sampled[1][index * side], seed, "X 窗口接缝");
            require(sampled[0][(side - 1) * side + index] == sampled[2][index], seed, "Z 窗口接缝");
        }

        var restarted = new LongMountainRange(seed, 0, 0);
        IntStream.range(0, side * side).parallel().forEach(tick -> {
            int reverse = side * side - 1 - tick;
            for (int index = WINDOWS.length - 1; index >= 0; index--) {
                require(sampled[index][reverse] == height(restarted, WINDOWS[index], reverse, side),
                        seed, "反向并行采样");
            }
        });
        for (int axis = -2; axis <= 2; axis++) {
            int boundary = axis * 16;
            double center = range.sample(boundary, boundary, LAND, BASE).height();
            require(Math.abs(range.sample(boundary - 1, boundary, LAND, BASE).height() - center) < 3.0,
                    seed, "X 区块边界连续");
            require(Math.abs(range.sample(boundary, boundary - 1, LAND, BASE).height() - center) < 3.0,
                    seed, "Z 区块边界连续");
        }
    }

    private static LongMountainRange.Sample sample(LongMountainRange range, double along, double across, double land) {
        var point = range.position(along, across);
        return range.sample(point.x(), point.z(), land, BASE);
    }

    private static double height(LongMountainRange range, int[] window, int index, int side) {
        return range.sample(window[0] + index % side * 4, window[1] + index / side * 4, LAND, BASE).height();
    }

    private static void require(boolean condition, long seed, String behavior) {
        if (!condition) {
            throw new IllegalStateException("种子 " + seed + " 未通过：" + behavior);
        }
    }
}
