package com.zoneraids.world;

/**
 * Small deterministic value noise. The generated maps must be identical on every server, so the
 * layout never depends on the world seed.
 */
final class Noise {
    private final long seed;

    Noise(long seed) {
        this.seed = seed;
    }

    /** Uniform value in [0, 1) for an integer coordinate. */
    double hash(int x, int z) {
        return hash(seed, x, z);
    }

    static double hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth value noise in [-1, 1]. */
    double value(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fz = smooth(z - z0);
        double a = hash(x0, z0);
        double b = hash(x0 + 1, z0);
        double c = hash(x0, z0 + 1);
        double d = hash(x0 + 1, z0 + 1);
        double top = a + (b - a) * fx;
        double bottom = c + (d - c) * fx;
        return (top + (bottom - top) * fz) * 2.0 - 1.0;
    }

    /** Fractal noise in roughly [-1, 1]; {@code scale} is the size of the largest features in blocks. */
    double fbm(double x, double z, double scale, int octaves) {
        double sum = 0;
        double amp = 1;
        double norm = 0;
        double freq = 1.0 / scale;
        for (int i = 0; i < octaves; i++) {
            sum += value(x * freq + i * 31.7, z * freq - i * 17.3) * amp;
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return sum / norm;
    }

    static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    static double smoothstep(double edge0, double edge1, double v) {
        double t = Math.max(0, Math.min(1, (v - edge0) / (edge1 - edge0)));
        return smooth(t);
    }
}
