package dev.betterterrain.worldgen.landscape;

import java.util.SplittableRandom;

/** 在世界坐标中采样单条连续山带。 */
public final class LongMountainRange {
    private static final long RANGE_SALT = 0x64C02FBDE6A7AC51L;
    private static final int SEGMENTS = 8;
    private static final double BACKGROUND_EROSION = 0.86;
    private static final double CREST_EROSION = 0.16;
    private static final Shape SINGLE_RANGE = new Shape(2_560, 720, 190, 150, 230, 180);
    private static final Shape REGIONAL_RANGE = new Shape(512, 610, 95, 126, 96, 80);

    public static final double FIRST_SUMMIT = 0.25;
    public static final double SECOND_SUMMIT = 0.75;

    private final int centerX;
    private final int centerZ;
    private final double length;
    private final double halfWidth;
    private final double cosine;
    private final double sine;
    private final double phase;
    private final double[] bends = new double[SEGMENTS + 1];
    private final double[] relief = new double[SEGMENTS + 1];
    private final double[] widths = new double[SEGMENTS + 1];
    private final double[] absoluteHeights = new double[SEGMENTS + 1];

    public LongMountainRange(long seed, int centerX, int centerZ) {
        this(seed, centerX, centerZ, Double.NaN, SINGLE_RANGE, Bend.S_CURVE);
    }

    /** 区域山带的方向以弧度计，可为两个或三个主峰指定绝对高程。 */
    public LongMountainRange(long seed, int centerX, int centerZ, double direction, Bend bend, double... summitHeights) {
        this(seed, centerX, centerZ, direction, REGIONAL_RANGE, bend, summitHeights);
    }

    private LongMountainRange(long seed, int centerX, int centerZ, double direction, Shape shape, Bend bend,
                              double... summitHeights) {
        if (summitHeights.length != 0 && summitHeights.length != 2 && summitHeights.length != 3) {
            throw new IllegalArgumentException("summitHeights 需要两个或三个高程");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        SplittableRandom random = new SplittableRandom(seed ^ Long.rotateLeft((long) centerX, 17)
                ^ Long.rotateLeft((long) centerZ, 43) ^ RANGE_SALT);
        double defaultDirection = random.nextDouble() * StrictMath.PI;
        double angle = Double.isNaN(direction) ? defaultDirection : direction;
        if (!Double.isFinite(angle)) {
            throw new IllegalArgumentException("direction 必须为有限的弧度值");
        }
        cosine = StrictMath.cos(angle);
        sine = StrictMath.sin(angle);
        length = 5_120.0 + shape.lengthVariation() * random.nextDouble();
        halfWidth = shape.minimumHalfWidth() + shape.widthVariation() * random.nextDouble();
        phase = random.nextDouble() * 2.0 * StrictMath.PI;
        double turning = shape.minimumBend() + shape.bendVariation() * random.nextDouble();

        for (int station = 0; station <= SEGMENTS; station++) {
            double progress = (double) station / SEGMENTS;
            double curve = switch (bend) {
                case NEAR_STRAIGHT -> 0.22 * StrictMath.sin(2.0 * StrictMath.PI * progress + phase);
                case ARC -> StrictMath.copySign(StrictMath.sin(StrictMath.PI * progress), phase - StrictMath.PI);
                case S_CURVE -> StrictMath.sin(2.0 * StrictMath.PI * progress + phase);
            };
            bends[station] = turning * curve
                    + shape.stationVariation() * (random.nextDouble() - 0.5);
            double amplitude = summitHeights.length == 0 ? (station % 2 == 0 ? 90.0 : 62.0) : 34.0;
            relief[station] = 100.0 + amplitude * random.nextDouble();
            widths[station] = 0.91 + 0.13 * random.nextDouble();
        }
        double middleBend = bends[SEGMENTS / 2];
        for (int station = 0; station <= SEGMENTS; station++) {
            bends[station] -= middleBend;
        }
        if (summitHeights.length == 0) {
            absoluteHeights[2] = 398.0 + 10.0 * random.nextDouble();
            absoluteHeights[6] = 392.0 + 6.0 * random.nextDouble();
        } else {
            for (int index = 0; index < summitHeights.length; index++) {
                double altitude = summitHeights[index];
                if (!(altitude > 0.0 && altitude < 512.0)) {
                    throw new IllegalArgumentException("summitHeights[" + index + "] 必须在 Y=0 与 Y=512 之间");
                }
                absoluteHeights[summitStation(index, summitHeights.length)] = altitude;
            }
        }
    }

    /** 大陆度的输入范围为 [-1, 1]，从 0 到 0.3 平滑过渡。高程和坐标均以方块计。 */
    public Sample sample(double x, double z, double continentalness, double baseHeight) {
        double offsetX = x - centerX;
        double offsetZ = z - centerZ;
        double progress = (offsetX * cosine + offsetZ * sine) / length + 0.5;
        if (progress <= 0.0 || progress >= 1.0 || continentalness <= 0.0) {
            return new Sample(baseHeight, BACKGROUND_EROSION, 0.0);
        }

        double lateral = -offsetX * sine + offsetZ * cosine - bend(progress);
        double side = lateral < 0.0 ? -1.0 : 1.0;
        double distance = StrictMath.abs(lateral);
        double width = halfWidth * interpolate(widths, progress) * (1.0 + 0.06 * side);
        double endFade = smooth(StrictMath.min(progress, 1.0 - progress) / 0.16);
        if (distance >= width) {
            return new Sample(baseHeight, BACKGROUND_EROSION, 0.0);
        }

        // 山肩的宽度随走向和两侧变化，活动范围始终受山带外缘限制。
        double widthVariation = StrictMath.sin(progress * 58.0 + phase + side * 1.4 - distance / 145.0);
        double shoulderWidth = width * (0.32 + 0.09 * widthVariation);
        double crossSection = 0.64 * (1.0 - smooth(distance / shoulderWidth))
                + 0.36 * (1.0 - smooth(distance / width));
        double weight = endFade * crossSection * smooth(continentalness / 0.3);
        double erosion = mix(BACKGROUND_EROSION, CREST_EROSION, weight);
        double regularRelief = StrictMath.max(0.0, StrictMath.min(190.0, 512.0 - baseHeight));
        int index = station(progress);
        double left = reliefAtStation(index, baseHeight, regularRelief);
        double right = reliefAtStation(index + 1, baseHeight, regularRelief);
        double height = baseHeight + (BACKGROUND_EROSION - erosion)
                / (BACKGROUND_EROSION - CREST_EROSION) * mix(left, right, smooth(fraction(progress)));
        return new Sample(height, erosion, weight);
    }

    public Position position(double progress, double across) {
        if (!(progress >= 0.0 && progress <= 1.0)) {
            throw new IllegalArgumentException("山脊进度 progress 的有效范围为 [0, 1]");
        }
        double along = (progress - 0.5) * length;
        double lateral = bend(progress) + across;
        return new Position(centerX + along * cosine - lateral * sine,
                centerZ + along * sine + lateral * cosine);
    }

    public double length() {
        return length;
    }

    public static double summitProgress(int index, int count) {
        return summitStation(index, count) / (double) SEGMENTS;
    }

    private static int summitStation(int index, int count) {
        if ((count != 2 && count != 3) || index < 0 || index >= count) {
            throw new IllegalArgumentException("无效的主峰序号或数量");
        }
        return count == 2 ? 2 + index * 4 : 2 + index * 2;
    }

    private double reliefAtStation(int station, double baseHeight, double regularRelief) {
        if (absoluteHeights[station] > 0.0) {
            return StrictMath.max(0.0, absoluteHeights[station] - baseHeight);
        }
        return StrictMath.min(relief[station], regularRelief);
    }

    private double bend(double progress) {
        int index = station(progress);
        double previous = bends[StrictMath.max(0, index - 1)];
        double start = bends[index];
        double end = bends[index + 1];
        double next = bends[StrictMath.min(SEGMENTS, index + 2)];
        double t = fraction(progress);
        double t2 = t * t;
        double t3 = t2 * t;
        return (2.0 * t3 - 3.0 * t2 + 1.0) * start + (t3 - 2.0 * t2 + t) * (end - previous) / 2.0
                + (-2.0 * t3 + 3.0 * t2) * end + (t3 - t2) * (next - start) / 2.0;
    }

    private static double interpolate(double[] values, double progress) {
        int index = station(progress);
        return mix(values[index], values[index + 1], smooth(fraction(progress)));
    }

    private static int station(double progress) {
        return StrictMath.min(SEGMENTS - 1, (int) (progress * SEGMENTS));
    }

    private static double fraction(double progress) {
        return progress * SEGMENTS - station(progress);
    }

    private static double smooth(double t) {
        double clamped = StrictMath.clamp(t, 0.0, 1.0);
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public record Position(double x, double z) {}

    public record Sample(double height, double erosion, double mountainWeight) {}

    public enum Bend { NEAR_STRAIGHT, ARC, S_CURVE }

    private record Shape(double lengthVariation, double minimumHalfWidth, double widthVariation,
                         double minimumBend, double bendVariation, double stationVariation) {}
}
