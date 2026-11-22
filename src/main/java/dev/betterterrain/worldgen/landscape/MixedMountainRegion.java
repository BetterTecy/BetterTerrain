package dev.betterterrain.worldgen.landscape;

import dev.betterterrain.worldgen.landscape.LongMountainRange.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** 新版混合山地按世界坐标布置，旧版山脉预设继续使用原采样器。 */
public final class MixedMountainRegion {
    public static final int TILE_SIZE = 16_384;
    public static final double BASE = 118.0;
    private static final int HALF_TILE = TILE_SIZE / 2;
    private static final int NEIGHBOR_REACH = 3_584;
    private static final int CACHE_SIZE = 64;
    private static final int PATCH_SIZE = 1_024;
    private static final int PATCH_CACHE_SIZE = 512;
    private static final long SALT = 0x9F1D04AC8CBF2611L;
    private final long seed;
    private final AtomicReferenceArray<Tile> tiles = new AtomicReferenceArray<>(CACHE_SIZE);
    private final AtomicReferenceArray<Patch> patches = new AtomicReferenceArray<>(PATCH_CACHE_SIZE);

    public MixedMountainRegion(long seed) {
        this.seed = seed;
    }

    public enum Kind { LONG, ARC, PARALLEL, BRANCHED, MASSIF }

    public List<Feature> featuresInWindow(int x, int z, int size) {
        if (size <= 0) throw new IllegalArgumentException("窗口尺寸必须为正");
        double east = (double) x + size;
        double south = (double) z + size;
        List<Feature> result = new ArrayList<>();
        for (int row = tileIndex(z); row <= tileIndex(south - 1); row++) {
            for (int column = tileIndex(x); column <= tileIndex(east - 1); column++) {
                for (Feature feature : tile(column, row).features()) {
                    Position anchor = feature.anchor();
                    if (anchor.x() >= x && anchor.x() < east && anchor.z() >= z && anchor.z() < south) {
                        result.add(feature);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    public double height(double x, double z) {
        double highest = BASE;
        double second = BASE;
        for (Feature feature : patch((int) Math.floor(x / PATCH_SIZE), (int) Math.floor(z / PATCH_SIZE)).features()) {
            double surface = feature.height(x, z);
            second = Math.max(second, Math.min(surface, highest));
            highest = Math.max(highest, surface);
        }
        double width = Math.min(10.0, second - BASE);
        double difference = highest - second;
        if (difference >= width) return highest;
        double overlap = width - difference;
        return highest - overlap * overlap / (4.0 * width);
    }

    private Patch patch(int x, int z) {
        long key = mix(((long) x << 32) | Integer.toUnsignedLong(z));
        int index = (int) key & (PATCH_CACHE_SIZE - 1);
        Patch cached = patches.get(index);
        if (cached != null && cached.x() == x && cached.z() == z) return cached;
        double west = (double) x * PATCH_SIZE;
        double north = (double) z * PATCH_SIZE;
        var area = new Bounds(west, north, west + PATCH_SIZE, north + PATCH_SIZE);
        List<Feature> candidates = new ArrayList<>();
        int east = tileIndex(area.east() + NEIGHBOR_REACH);
        int south = tileIndex(area.south() + NEIGHBOR_REACH);
        for (int row = tileIndex(north - NEIGHBOR_REACH); row <= south; row++) {
            for (int column = tileIndex(west - NEIGHBOR_REACH); column <= east; column++) {
                for (Feature feature : tile(column, row).features()) {
                    if (feature.intersects(area)) candidates.add(feature);
                }
            }
        }
        Patch result = new Patch(x, z, candidates.toArray(Feature[]::new));
        patches.set(index, result);
        return result;
    }

    private Tile tile(int x, int z) {
        long key = mix(((long) x << 32) | Integer.toUnsignedLong(z));
        int index = (int) key & (CACHE_SIZE - 1);
        Tile cached = tiles.get(index);
        if (cached != null && cached.x() == x && cached.z() == z) return cached;
        Tile result = createTile(x, z, mix(seed ^ key ^ SALT));
        tiles.set(index, result);
        return result;
    }

    private static int tileIndex(double coordinate) {
        return (int) Math.floor((coordinate + HALF_TILE) / TILE_SIZE);
    }

    private static Tile createTile(int x, int z, long salt) {
        var random = new SplittableRandom(salt);
        double centerX = (double) x * TILE_SIZE;
        double centerZ = (double) z * TILE_SIZE;
        int[] cells = {0, 1, 2, 3};
        for (int i = cells.length - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            int saved = cells[i];
            cells[i] = cells[other];
            cells[other] = saved;
        }
        int firstTall = random.nextInt(8);
        int secondTall = (firstTall + random.nextInt(1, 8)) % 8;
        double bearing = random.nextDouble(Math.PI);
        List<Feature> features = new ArrayList<>(9);
        for (int group = 0; group < cells.length; group++) {
            int cell = cells[group];
            var local = new SplittableRandom(mix(salt + group));
            double angle = bearing + group * Math.PI / 4 + local.nextDouble(-0.18, 0.18);
            double cellX = centerX + ((cell & 1) == 0 ? -4_096 : 4_096) + local.nextDouble(-240, 240);
            double cellZ = centerZ + ((cell & 2) == 0 ? -4_096 : 4_096) + local.nextDouble(-240, 240);
            boolean parallel = group == 0;
            double split = parallel ? local.nextDouble(710, 820) : group == 2 ? 1_550 : 1_360;
            double sharedLength = local.nextDouble(4_990, 5_580);
            double peakShift = local.nextDouble(600, 850);
            for (int side = 0; side < 2; side++) {
                int slot = group * 2 + side;
                var shape = new SplittableRandom(mix(salt ^ 0xA71F0EE98B32456DL ^ slot));
                boolean tall = slot == firstTall || slot == secondTall;
                Kind kind = parallel ? Kind.PARALLEL : side == 1 ? Kind.LONG : switch (group) {
                    case 1 -> Kind.ARC;
                    case 2 -> Kind.BRANCHED;
                    default -> Kind.LONG;
                };
                String id = "ridge-" + x + "_" + z + "_" + slot;
                double along = parallel ? (side - 0.5) * peakShift : shape.nextDouble(-250, 250);
                Position anchor = offset(cellX, cellZ, angle, along, side == 0 ? -split : split);
                double length = switch (kind) {
                    case ARC -> shape.nextDouble(5_120, 6_144);
                    case PARALLEL -> sharedLength - side * 280;
                    default -> shape.nextDouble(5_120, 5_632);
                };
                double turn = switch (kind) {
                    case ARC -> -Math.toRadians(shape.nextDouble(65, 110));
                    case PARALLEL -> 0.0;
                    default -> Math.toRadians(shape.nextDouble(-16, 16));
                };
                double low = parallel && side == 1 ? 273 : 310;
                double high = parallel && side == 1 ? 333 : 367;
                double[] summits = new double[kind == Kind.ARC && shape.nextBoolean() ? 3 : 2];
                Arrays.setAll(summits, ignored -> shape.nextDouble(low, high));
                if (tall) summits[shape.nextInt(summits.length)] = shape.nextDouble(392, 408);
                Ridge main = Ridge.withSummits(anchor, angle, length, shape.nextDouble(610, 720), turn, summits);
                List<Ridge> branches = kind == Kind.BRANCHED ? branches(main, shape) : List.of();
                features.add(new Feature(id, kind, anchor, main, branches, null));
            }
        }
        var massif = new Massif(new SplittableRandom(mix(salt ^ 0x21D2771C67131A49L)), centerX, centerZ);
        features.add(new Feature("massif-" + x + "_" + z, Kind.MASSIF, massif.center,
                massif.ridges.getFirst(), massif.ridges.subList(1, massif.ridges.size()), massif));
        return new Tile(x, z, List.copyOf(features));
    }

    private static List<Ridge> branches(Ridge main, SplittableRandom random) {
        int count = random.nextInt(1, 3);
        List<Ridge> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double join = count == 1 ? random.nextDouble(0.39, 0.61) : 0.34 + index * 0.30;
            double direction = main.bearing(join) - Math.toRadians(random.nextDouble(33, 64));
            double length = random.nextDouble(1_280, 2_300);
            Position root = main.position(join, 0);
            Position center = offset(root.x(), root.z(), direction, length / 2, 0);
            double summit = Math.min(main.highest() - 24, random.nextDouble(244, 303));
            result.add(new Ridge(center, direction, length, random.nextDouble(345, 495), 0,
                    new double[]{main.crest(join), summit, summit - 43, BASE}, List.of(1.0 / 3)));
        }
        return List.copyOf(result);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static Position offset(double x, double z, double bearing, double along, double across) {
        double cosine = Math.cos(bearing);
        double sine = Math.sin(bearing);
        return new Position(x + along * cosine - across * sine, z + along * sine + across * cosine);
    }

    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private record Tile(int x, int z, List<Feature> features) {}
    private record Patch(int x, int z, Feature[] features) {}

    private record Bounds(double west, double north, double east, double south) {
        private boolean intersects(Bounds area) {
            return west <= area.east && north <= area.south && east >= area.west && south >= area.north;
        }
    }

    public record Feature(String id, Kind kind, Position anchor, Ridge main, List<Ridge> connections, Massif massif) {
        public List<Position> summits() {
            return massif == null ? main.summits() : massif.peaks;
        }

        public double height(double x, double z) {
            if (massif != null) return massif.height(x, z);
            double height = main.height(x, z);
            for (Ridge branch : connections) height = Math.max(height, branch.height(x, z));
            return height;
        }

        private boolean intersects(Bounds area) {
            if (massif != null) return massif.bounds.intersects(area);
            if (main.bounds.intersects(area)) return true;
            for (Ridge branch : connections) {
                if (branch.bounds.intersects(area)) return true;
            }
            return false;
        }
    }

    public static final class Ridge {
        private final Position anchor;
        private final double direction;
        private final double cosine;
        private final double sine;
        private final double length;
        private final double width;
        private final double turn;
        private final double radius;
        private final double reach;
        private final Bounds bounds;
        private final double[] crest;
        private final List<Double> summitProgress;

        private Ridge(Position anchor, double direction, double length, double width, double turn,
                      double[] crest, List<Double> summitProgress) {
            this.anchor = anchor;
            this.direction = direction;
            this.cosine = Math.cos(direction);
            this.sine = Math.sin(direction);
            this.length = length;
            this.width = width;
            this.turn = turn;
            this.radius = turn == 0 ? 0 : length / turn;
            this.reach = length / 2 + width;
            this.crest = crest;
            this.summitProgress = summitProgress;
            this.bounds = buildBounds();
        }

        private Bounds buildBounds() {
            double west = anchor.x();
            double east = west;
            double north = anchor.z();
            double south = north;
            for (int station = 0; station <= 8; station++) {
                Position point = position(station / 8.0, 0);
                west = Math.min(west, point.x());
                east = Math.max(east, point.x());
                north = Math.min(north, point.z());
                south = Math.max(south, point.z());
            }
            // 补上相邻采样站点之间可能达到的弧长，再向两侧扩到山脚。
            double margin = width + length / 16;
            return new Bounds(west - margin, north - margin, east + margin, south + margin);
        }

        private static Ridge withSummits(Position anchor, double direction, double length, double width, double turn,
                                         double[] summits) {
            var profile = new double[summits.length * 2 + 1];
            profile[0] = BASE;
            profile[profile.length - 1] = BASE;
            List<Double> stations = new ArrayList<>(summits.length);
            for (int index = 0; index < summits.length; index++) {
                int station = index * 2 + 1;
                profile[station] = summits[index];
                stations.add((double) station / (profile.length - 1));
                if (index > 0) profile[station - 1] = Math.min(summits[index - 1], summits[index]) - 52;
            }
            return new Ridge(anchor, direction, length, width, turn, profile, List.copyOf(stations));
        }

        public double length() { return length; }
        public double turn() { return turn; }
        public double bearing(double progress) { return direction + (progress - 0.5) * turn; }
        public List<Position> summits() { return summitProgress.stream().map(t -> position(t, 0)).toList(); }

        public Position position(double progress, double across) {
            if (turn == 0) return offset(anchor.x(), anchor.z(), direction, (progress - 0.5) * length, across);
            double angle = (progress - 0.5) * turn;
            double reach = radius - across;
            return offset(anchor.x(), anchor.z(), direction, reach * Math.sin(angle), radius - reach * Math.cos(angle));
        }

        private double highest() {
            return Arrays.stream(crest).max().orElseThrow();
        }

        private double crest(double progress) {
            double station = Math.clamp(progress, 0, 1) * (crest.length - 1);
            int index = Math.min((int) station, crest.length - 2);
            return crest[index] + (crest[index + 1] - crest[index]) * smooth(station - index);
        }

        public double height(double x, double z) {
            double dx = x - anchor.x();
            double dz = z - anchor.z();
            if (Math.abs(dx) >= reach || Math.abs(dz) >= reach) return BASE;
            double along = dx * cosine + dz * sine;
            double across = dz * cosine - dx * sine;
            double progress;
            if (turn == 0) {
                progress = along / length + 0.5;
            } else {
                double radialZ = radius - across;
                double radialDistance = Math.sqrt(along * along + radialZ * radialZ);
                across = radius - Math.copySign(radialDistance, turn);
                if (Math.abs(across) >= width) return BASE;
                double sign = Math.copySign(1, turn);
                progress = Math.atan2(along * sign, radialZ * sign) / turn + 0.5;
            }
            double flank = across * turn > 0 ? width * 0.83 : width;
            double distance = Math.abs(across);
            if (progress < 0 || progress > 1) {
                double endpoint = Math.clamp(progress, 0, 1);
                if (crest(endpoint) == BASE) return BASE;
                distance = Math.hypot(distance, (progress - endpoint) * length);
            }
            if (distance >= flank) return BASE;
            double shoulder = 0.65 * smooth(1 - distance / (flank * 0.47)) + 0.35 * smooth(1 - distance / flank);
            return BASE + (crest(progress) - BASE) * shoulder;
        }
    }

    public static final class Massif {
        private final Position center;
        private final double major;
        private final double minor;
        private final double bearing;
        private final double cosine;
        private final double sine;
        private final List<Position> peaks;
        private final double[] summit;
        private final List<Ridge> ridges;
        private final double valleyA;
        private final double valleyB;
        private final Bounds bounds;

        private Massif(SplittableRandom random, double x, double z) {
            center = new Position(x + random.nextDouble(-320, 320), z + random.nextDouble(-320, 320));
            major = random.nextDouble(1_340, 1_680);
            minor = Math.max(major / 1.55, random.nextDouble(1_090, 1_330));
            double reach = major * 1.16;
            bounds = new Bounds(center.x() - reach, center.z() - reach, center.x() + reach, center.z() + reach);
            bearing = random.nextDouble(2 * Math.PI);
            cosine = Math.cos(bearing);
            sine = Math.sin(bearing);
            int count = random.nextInt(3, 6);
            double step = 2 * Math.PI / count;
            summit = new double[count];
            List<Position> points = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                double angle = index * step;
                points.add(offset(center.x(), center.z(), bearing, major * 0.51 * Math.cos(angle),
                        minor * 0.58 * Math.sin(angle)));
                summit[index] = random.nextDouble(260, 345);
            }
            peaks = List.copyOf(points);
            valleyA = step / 2;
            valleyB = (count / 2 + 0.5) * step;
            List<Ridge> links = new ArrayList<>(count - 1);
            for (int index = 1; index < count; index++) {
                Position first = peaks.get(index - 1);
                Position second = peaks.get(index);
                double dx = second.x() - first.x();
                double dz = second.z() - first.z();
                double saddle = Math.min(summit[index - 1], summit[index]) - random.nextDouble(48, 67);
                links.add(new Ridge(new Position(first.x() + dx / 2, first.z() + dz / 2),
                        Math.atan2(dz, dx), Math.hypot(dx, dz), 270, 0,
                        new double[]{summit[index - 1], saddle, summit[index]}, List.of(0.0, 1.0)));
            }
            ridges = List.copyOf(links);
        }

        private double height(double x, double z) {
            double dx = x - center.x();
            double dz = z - center.z();
            if (Math.abs(dx) >= major * 1.16 || Math.abs(dz) >= major * 1.16) return BASE;
            double u = (dx * cosine + dz * sine) / major;
            double v = (dz * cosine - dx * sine) / minor;
            double radius = Math.sqrt(u * u + v * v);
            double outside = 1 + 0.11 * u - 0.08 * v;
            if (radius >= outside) return BASE;
            double direction = Math.atan2(v, u);
            double opening = Math.max(Math.cos(direction - valleyA), Math.cos(direction - valleyB));
            double valley = smooth((radius - 0.48) / 0.32) * smooth((opening - 0.89) / 0.11);
            double surface = BASE + 72 * smooth(1 - radius / outside) * (1 - 0.82 * valley);
            for (int index = 0; index < peaks.size(); index++) {
                Position peak = peaks.get(index);
                double peakX = x - peak.x();
                double peakZ = z - peak.z();
                double distance = Math.sqrt(peakX * peakX + peakZ * peakZ);
                surface = Math.max(surface, BASE + (summit[index] - BASE) * smooth(1 - distance / 610));
            }
            for (Ridge ridge : ridges) surface = Math.max(surface, ridge.height(x, z));
            return BASE + (surface - BASE) * smooth((outside - radius) / 0.22);
        }
    }
}
