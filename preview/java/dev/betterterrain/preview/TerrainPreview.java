package dev.betterterrain.preview;

import com.google.gson.Gson;
import com.sun.net.httpserver.SimpleFileServer;
import dev.betterterrain.worldgen.landscape.LongMountainRange;
import dev.betterterrain.worldgen.landscape.LongMountainRange.Position;
import dev.betterterrain.worldgen.landscape.MountainRegion;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TerrainPreview {
    static final long[] SEEDS = {42L, 12345L};
    static final int[][] VIEW_CENTERS = {{0, 0}, {98_304, -65_536}, {-131_072, 81_920}};
    static final double LAND = 0.72;
    static final double BASE_HEIGHT = 118.0;
    private static final int SEA_LEVEL = 63;
    private static final int CLOUD_LEVEL = 384;
    private static final int WORLD_TOP = 512;
    static final int GRID_SIZE = 256;
    static final int OVERVIEW = 16_384;
    private static final int SECTION = 4_096;
    private static final int DETAIL = 1_024;
    private static final int BLOCKS = 256;

    private TerrainPreview() {}

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && "check".equals(args[0])) {
            TerrainPreviewCheck.run();
            return;
        }
        if (args.length == 3 && "serve".equals(args[0])) {
            Path directory = Path.of(args[1]).toAbsolutePath();
            int port = Integer.parseInt(args[2]);
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
            var server = SimpleFileServer.createFileServer(address, directory, SimpleFileServer.OutputLevel.NONE);
            server.start();
            System.out.println("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            return;
        }
        if (args.length != 2 || !"export".equals(args[0])) {
            throw new IllegalArgumentException("预览命令为 check、export <目录> 或 serve <目录> <端口>");
        }
        Path directory = Path.of(args[1]).toAbsolutePath();
        List<SeedData> worlds = new ArrayList<>();
        for (long seed : SEEDS) {
            var region = new MountainRegion(seed);
            for (int sample = 0; sample < VIEW_CENTERS.length; sample++) {
                worlds.add(exportWindow(directory, region, seed, sample, VIEW_CENTERS[sample]));
            }
        }
        var manifest = new Manifest("betterterrain:long_mountain_preview", 4, SEA_LEVEL, CLOUD_LEVEL,
                WORLD_TOP, BASE_HEIGHT, GRID_SIZE, worlds);
        Files.writeString(directory.resolve("terrain.json"), new Gson().toJson(manifest));
        System.out.printf("已导出 %d 个山脉观察窗口到 %s%n", worlds.size(), directory);
    }

    private static SeedData exportWindow(Path directory, MountainRegion region, long seed, int sample,
                                         int[] center) throws IOException {
        long started = System.nanoTime();
        String seedId = Long.toString(seed);
        String folder = sample == 0 ? seedId : seedId + "-" + sample;
        Files.createDirectories(directory.resolve(folder));
        Window overview = grid(directory, region, folder + "/overview", center[0] - OVERVIEW / 2,
                center[1] - OVERVIEW / 2, OVERVIEW);
        List<MountainRegion.Band> bands = region.bandsInWindow(overview.x0(), overview.z0(), overview.size());
        List<Landmark> landmarks = new ArrayList<>();
        List<List<ProfilePoint>> ridges = new ArrayList<>();
        for (int bandIndex = 0; bandIndex < bands.size(); bandIndex++) {
            var band = bands.get(bandIndex);
            ridges.add(longitudinal(region, band.range()));
            landmarks.add(landmark(directory, folder, region, overview, band, bandIndex));
        }
        var data = new SeedData(folder, seedId, overview, landmarks, ridges);
        System.out.printf("种子 %d，中心 (%d, %d)：%d 条山脉，%d 个高程网格，导出耗时 %d ms%n", seed,
                center[0], center[1], landmarks.size(),
                1 + 3 * landmarks.size(),
                (System.nanoTime() - started) / 1_000_000L);
        return data;
    }

    private static double highestSummit(MountainRegion region, MountainRegion.Band band, Window overview) {
        double bestProgress = 0.5;
        double bestHeight = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < band.peakCount(); index++) {
            double progress = LongMountainRange.summitProgress(index, band.peakCount());
            Position point = band.range().position(progress, 0);
            if (!safelyInside(point.x(), overview.x0(), overview.size())
                    || !safelyInside(point.z(), overview.z0(), overview.size())) continue;
            double sample = height(region, point);
            if (sample > bestHeight) {
                bestHeight = sample;
                bestProgress = progress;
            }
        }
        return bestProgress;
    }

    private static boolean safelyInside(double coordinate, int origin, int size) {
        return coordinate >= origin + 128 && coordinate < origin + size - 128;
    }

    private static Landmark landmark(Path directory, String folder, MountainRegion region, Window overview,
                                     MountainRegion.Band band, int index) throws IOException {
        String id = "band-" + band.id().cellX() + "_" + band.id().cellZ() + "_" + band.id().slot();
        var range = band.range();
        double along = highestSummit(region, band, overview);
        Position point = range.position(along, 0);
        String path = folder + "/" + id;
        Window section = nestedGrid(directory, region, path + "-section", overview, point, SECTION);
        Window detail = nestedGrid(directory, region, path + "-detail", section, point, DETAIL);
        Position flank = range.position(along, 84);
        Window blocks = nestedGrid(directory, region, path + "-blocks", detail, flank, BLOCKS);
        return new Landmark(id, index, point.x(), point.z(), height(region, point),
                section, detail, blocks, transverse(region, range, along));
    }

    private static Window nestedGrid(Path directory, MountainRegion region, String path, Window parent,
                                     Position center, int size) throws IOException {
        int step = parent.stride();
        int x = nestedOrigin(center.x(), size, parent.x0(), parent.size(), step);
        int z = nestedOrigin(center.z(), size, parent.z0(), parent.size(), step);
        Window child = grid(directory, region, path, x, z, size);
        checkNested(parent, child);
        return child;
    }

    private static void checkNested(Window parent, Window child) {
        int x = child.x0() - parent.x0();
        int z = child.z0() - parent.z0();
        if (x <= 0 || z <= 0 || x + child.size() >= parent.size() || z + child.size() >= parent.size()
                || x % parent.stride() != 0 || z % parent.stride() != 0 || child.size() % parent.stride() != 0) {
            throw new IllegalStateException("精细窗口未对齐其父窗口：" + child.file());
        }
    }

    private static int nestedOrigin(double center, int size, int origin, int parentSize, int alignment) {
        int desired = (int) Math.round((center - origin - size / 2.0) / alignment) * alignment + origin;
        return Math.clamp(desired, origin + alignment, origin + parentSize - size - alignment);
    }

    private static Window grid(Path directory, MountainRegion region, String name,
                               int x0, int z0, int size) throws IOException {
        String file = name + ".i16";
        int stride = size / GRID_SIZE;
        ByteBuffer bytes = ByteBuffer.allocate(GRID_SIZE * GRID_SIZE * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int column = 0; column < GRID_SIZE; column++) {
                int height = columnHeight(region, x0 + column * stride, z0 + row * stride, stride);
                bytes.putShort((short) height);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
            }
        }
        Files.write(directory.resolve(file), bytes.array());
        return new Window(file, x0, z0, size, stride, minimum, maximum);
    }

    static int columnHeight(MountainRegion region, int x, int z, int stride) {
        return (int) Math.floor(region.height(x + stride / 2, z + stride / 2, LAND, BASE_HEIGHT));
    }

    static List<ProfilePoint> longitudinal(MountainRegion region, LongMountainRange range) {
        List<ProfilePoint> points = new ArrayList<>();
        Position last = range.position(0.0, 0.0);
        double distance = 0.0;
        for (int index = 0; index <= 256; index++) {
            Position point = range.position(index / 256.0, 0.0);
            distance += Math.hypot(point.x() - last.x(), point.z() - last.z());
            points.add(new ProfilePoint(point.x(), point.z(), distance, height(region, point)));
            last = point;
        }
        return points;
    }

    private static List<ProfilePoint> transverse(MountainRegion region, LongMountainRange range, double along) {
        List<ProfilePoint> points = new ArrayList<>();
        for (int across = -1_200; across <= 1_200; across += 12) {
            Position point = range.position(along, across);
            points.add(new ProfilePoint(point.x(), point.z(), across + 1_200, height(region, point)));
        }
        return points;
    }

    private static double height(MountainRegion region, Position point) {
        return region.height(point.x(), point.z(), LAND, BASE_HEIGHT);
    }

    record Manifest(String preset, int version, int seaLevel, int cloudLevel, int worldTop,
                    double baseHeight, int gridSize, List<SeedData> seeds) {}

    record SeedData(String id, String seed, Window overview, List<Landmark> landmarks, List<List<ProfilePoint>> ridges) {}

    record Landmark(String id, int band, double x, double z, double y,
                    Window section, Window detail, Window blocks, List<ProfilePoint> cross) {}

    record Window(String file, int x0, int z0, int size, int stride, int min, int max) {}

    record ProfilePoint(double x, double z, double distance, double height) {}
}
