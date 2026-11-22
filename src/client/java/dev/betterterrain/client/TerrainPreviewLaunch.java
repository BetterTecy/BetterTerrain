package dev.betterterrain.client;

import dev.betterterrain.BetterTerrain;
import dev.betterterrain.worldgen.landscape.LongMountainRange;
import dev.betterterrain.worldgen.landscape.LongMountainRange.Position;
import dev.betterterrain.worldgen.landscape.MountainHeight;
import dev.betterterrain.worldgen.landscape.MountainRegion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;

public final class TerrainPreviewLaunch {
    private static final Identifier PRESET_ID = Identifier.fromNamespaceAndPath("betterterrain", "terrain_preview");
    private static TerrainPreviewLaunch active;
    private final Path result;
    private final long seed;
    private final GameType mode;
    private final String worldId;
    private boolean requested;
    private boolean creating;
    private boolean finished;

    private TerrainPreviewLaunch(Path request) throws IOException {
        result = request.resolveSibling("result.txt");
        Properties options = new Properties();
        try (Reader reader = Files.newBufferedReader(request)) {
            options.load(reader);
        }
        seed = Long.parseLong(options.getProperty("seed"));
        mode = GameType.byName(options.getProperty("gameMode"), null);
        if (mode == null) throw new IllegalArgumentException("无效的预览游戏模式");
        worldId = "betterterrain-mountains-" + seed;
    }

    static void register() {
        String path = System.getenv("BETTERTERRAIN_PREVIEW_REQUEST");
        if (path == null || path.isBlank()) return;
        Path request = Path.of(path);
        try {
            TerrainPreviewLaunch launch = new TerrainPreviewLaunch(request);
            active = launch;
            ClientTickEvents.END_CLIENT_TICK.register(launch::tick);
        } catch (IOException | RuntimeException error) {
            writeResult(request.resolveSibling("result.txt"), "ERROR Invalid preview request", error);
        }
    }

    public static boolean isPreviewReload(String folder, WorldGenSettings settings) {
        return active != null && active.requested && !active.creating && !active.finished
                && active.worldId.equals(folder) && active.seed == settings.options().seed()
                && !settings.options().isOldCustomizedWorld() && hasPreviewGenerator(settings.dimensions().overworld());
    }

    private static boolean hasPreviewGenerator(ChunkGenerator generator) {
        return generator instanceof NoiseBasedChunkGenerator noise
                && noise.generatorSettings().is(ResourceKey.create(Registries.NOISE_SETTINGS, PRESET_ID));
    }

    private void tick(Minecraft client) {
        if (finished) return;
        if (!requested && client.gui.screen() instanceof TitleScreen title && client.gui.overlay() == null) {
            requested = true;
            try {
                openWorld(client, title);
            } catch (RuntimeException error) {
                fail("World open failed", error);
            }
        }
        var server = client.getSingleplayerServer();
        if (requested && !finished && server != null && client.player != null) {
            var playerId = client.player.getUUID();
            finished = true;
            server.execute(() -> {
                try {
                    verifyAndPosition(server.overworld(), server.getPlayerList().getPlayer(playerId));
                } catch (RuntimeException error) {
                    fail("World verification failed", error);
                }
            });
        }
    }

    private void openWorld(Minecraft client, TitleScreen title) {
        var flows = client.createWorldOpenFlows();
        creating = !client.getLevelSource().levelExists(worldId);
        if (!creating) {
            flows.openWorld(worldId, () -> {
                client.gui.setScreen(new TitleScreen());
                fail("World reload was cancelled", null);
            });
            return;
        }
        boolean creative = mode == GameType.CREATIVE;
        var settings = new LevelSettings("BetterTerrain · " + seed, mode,
                new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), creative,
                WorldDataConfiguration.DEFAULT);
        flows.createFreshLevel(worldId, settings, new WorldOptions(seed, true, false),
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, PRESET_ID))
                        .value().createWorldDimensions(),
                title);
    }

    private void verifyAndPosition(ServerLevel level, ServerPlayer player) {
        if (player == null || level.getSeed() != seed || !hasPreviewGenerator(level.getChunkSource().getGenerator())) {
            throw new IllegalStateException("存档的主世界预设或种子与预览请求不一致");
        }
        boolean modeChanged = player.gameMode() != mode;
        if (modeChanged) player.setGameMode(mode);
        if (player.gameMode() != mode) throw new IllegalStateException("无法切换到请求的游戏模式");

        String measurement = creating ? positionAtMountain(level, player) : "";
        if (creating || modeChanged) level.getServer().getPlayerList().saveAll();
        BlockPos position = player.blockPosition();
        writeResult(result, "OK world=" + worldId + " seed=" + level.getSeed() + " mode=" + player.gameMode().getName()
                + " preset=" + PRESET_ID + " action=" + (creating ? "created" : "reopened")
                + " position=" + position.getX() + "," + position.getY() + "," + position.getZ() + measurement, null);
    }

    private String positionAtMountain(ServerLevel level, ServerPlayer player) {
        var mountains = new MountainRegion(seed);
        var spawn = player.blockPosition();
        int radius = MountainRegion.CELL_SIZE;
        Position crest = null;
        Position overlook = null;
        double closest = Double.POSITIVE_INFINITY;
        for (var band : mountains.bandsInWindow(spawn.getX() - radius, spawn.getZ() - radius, radius * 2)) {
            for (int index = 0; index < band.peakCount(); index++) {
                double progress = LongMountainRange.summitProgress(index, band.peakCount());
                Position peak = band.range().position(progress, 0);
                double distance = Math.hypot(peak.x() - spawn.getX(), peak.z() - spawn.getZ());
                if (distance < closest) {
                    closest = distance;
                    crest = peak;
                    overlook = band.range().position(progress, 144);
                }
            }
        }
        if (crest == null) throw new IllegalStateException("预览出生点附近没有山脉");

        int crestX = Mth.floor(crest.x());
        int crestZ = Mth.floor(crest.z());
        int peakY = surface(level, crestX, crestZ);
        int x = Mth.floor(overlook.x());
        int z = Mth.floor(overlook.z());
        int ground = surface(level, x, z);
        boolean creative = mode == GameType.CREATIVE;
        int y = creative ? Math.max(ground + 56, peakY + 42) : ground;
        float yaw = (float) Math.toDegrees(Math.atan2(x - crest.x(), crest.z() - z));
        float pitch = creative ? 22.0F : 0.0F;
        if (!player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), yaw, pitch, true)) {
            throw new IllegalStateException("无法到达山脉观察点");
        }
        if (creative) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        int modelY = Mth.floor(MountainHeight.height(mountains, crestX, crestZ));
        return " crest=" + crestX + "," + peakY + "," + crestZ
                + " modelSurface=" + modelY + " block=" + BuiltInRegistries.BLOCK.getKey(
                        level.getBlockState(new BlockPos(crestX, peakY - 1, crestZ)).getBlock());
    }

    private static int surface(ServerLevel level, int x, int z) {
        // 高度图读取前取得该区块，验收值来自实际生成的方块。
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private void fail(String message, Throwable error) {
        finished = true;
        writeResult(result, "ERROR " + message, error);
    }

    private static void writeResult(Path result, String message, Throwable error) {
        if (error != null) BetterTerrain.LOGGER.error(message, error);
        else BetterTerrain.LOGGER.info(message);
        try {
            Path temporary = result.resolveSibling("result.tmp");
            Files.writeString(temporary, message + "\n");
            Files.move(temporary, result, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            BetterTerrain.LOGGER.error("无法写入预览启动结果", exception);
        }
    }
}
