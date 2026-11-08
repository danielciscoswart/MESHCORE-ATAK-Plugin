package dev.meshcore.atak.mctak;

import dev.meshcore.atak.util.Bytes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/** Systematic LT / Robust Soliton math from the MCTAK v0.1 description. */
public final class Fountain {
    public static final double C = 0.1;
    public static final double DELTA = 0.5;

    private Fountain() {}

    public static final class Xorshift32 {
        private int state;

        public Xorshift32(int seed) {
            state = seed == 0 ? 0x6D2B79F5 : seed;
        }

        public int next() {
            int x = state;
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            state = x;
            return x;
        }
    }

    public static int seedFor(int transferId, int symIdx) {
        long mixed = (transferId & 0xFFFFFFFFL) * 31337L + (symIdx & 0xFFFFFFFFL) * 7919L;
        int seed = (int) mixed;
        return seed == 0 ? 0x6D2B79F5 : seed;
    }

    public static double[] cdf(int k) {
        double[] rho = new double[k];
        double[] tau = new double[k];
        rho[0] = 1.0 / k;
        for (int d = 2; d <= k; d++) {
            rho[d - 1] = 1.0 / (d * (d - 1.0));
        }
        double r = C * Math.log(k / DELTA) * Math.sqrt(k);
        int dStar = (int) Math.min(Math.ceil(k / r), k);
        for (int d = 1; d < dStar; d++) {
            tau[d - 1] = r / (d * (double) k);
        }
        double tauStar = r * Math.log(r / DELTA) / k;
        if (tauStar < 0) {
            tauStar = 0;
        }
        tau[dStar - 1] = tauStar;
        double beta = 0;
        for (int i = 0; i < k; i++) {
            beta += rho[i] + tau[i];
        }
        double[] out = new double[k];
        double acc = 0;
        for (int i = 0; i < k; i++) {
            acc += (rho[i] + tau[i]) / beta;
            out[i] = acc;
        }
        return out;
    }

    public static int sampleDegree(Xorshift32 rng, int k) {
        if (k <= 1) {
            return 1;
        }
        double[] table = cdf(k);
        double u = Integer.toUnsignedLong(rng.next()) / 4294967296.0;
        for (int d = 1; d <= k; d++) {
            if (table[d - 1] >= u) {
                return d;
            }
        }
        return k;
    }

    public static int[] neighbors(int transferId, int symIdx, int k) {
        if (symIdx < k) {
            return new int[] {symIdx};
        }
        Xorshift32 rng = new Xorshift32(seedFor(transferId, symIdx));
        int degree = sampleDegree(rng, k);
        if (k == 1) {
            return new int[] {0};
        }
        int[] idx = new int[degree];
        int n = 0;
        while (n < degree) {
            int pick = (int) (Integer.toUnsignedLong(rng.next()) % k);
            boolean dup = false;
            for (int j = 0; j < n; j++) {
                if (idx[j] == pick) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                idx[n++] = pick;
            }
        }
        return idx;
    }

    public static int mStar(int k) {
        double eps = k <= 10 ? 0.5 : (k <= 50 ? 0.35 : 0.28);
        return (int) Math.ceil((1.0 + eps) * k);
    }

    public static int roundTarget(int k, int round) {
        int m = mStar(k);
        double factor = round == 1 ? 1.25 : round == 2 ? 2.1 : 3.0;
        return (int) Math.ceil(factor * m);
    }

    public static int r4Extra(int k) {
        return (int) Math.ceil(0.5 * mStar(k));
    }

    public static int symIdxLimit(int k) {
        return roundTarget(k, 3) + r4Extra(k);
    }

    public static int blocksFor(int objLen) {
        return (objLen + Mctak.SYMBOL_LEN - 1) / Mctak.SYMBOL_LEN;
    }

    public static byte[][] split(byte[] object) {
        int k = blocksFor(object.length);
        byte[][] blocks = new byte[k][Mctak.SYMBOL_LEN];
        for (int i = 0; i < k; i++) {
            int from = i * Mctak.SYMBOL_LEN;
            int n = Math.min(Mctak.SYMBOL_LEN, object.length - from);
            if (n > 0) {
                System.arraycopy(object, from, blocks[i], 0, n);
            }
        }
        return blocks;
    }

    public static byte[] symbol(byte[][] blocks, int transferId, int symIdx) {
        int[] nb = neighbors(transferId, symIdx, blocks.length);
        byte[] out = new byte[Mctak.SYMBOL_LEN];
        for (int index : nb) {
            Bytes.xor(out, blocks[index]);
        }
        return out;
    }

    public static byte[] withCrc(byte[] prefix) {
        CRC32 crc = new CRC32();
        crc.update(prefix);
        byte[] out = Arrays.copyOf(prefix, prefix.length + 4);
        Bytes.putU32le(out, prefix.length, crc.getValue());
        return out;
    }

    public static boolean crcOk(byte[] object) {
        if (object.length < 5) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(object, 0, object.length - 4);
        return crc.getValue() == Bytes.u32le(object, object.length - 4);
    }

    public static final class SymbolIn {
        public final int symIdx;
        public final byte[] data;

        public SymbolIn(int symIdx, byte[] data) {
            this.symIdx = symIdx;
            this.data = Arrays.copyOf(data, Mctak.SYMBOL_LEN);
        }
    }

    /** Belief-propagation peel. Returns the object bytes, or null if not yet solved. */
    public static byte[] decode(int transferId, int k, int objLen, List<SymbolIn> symbols) {
        byte[][] known = new byte[k][Mctak.SYMBOL_LEN];
        boolean[] have = new boolean[k];
        boolean progress = true;
        while (progress) {
            progress = false;
            for (SymbolIn symbol : symbols) {
                int[] nb = neighbors(transferId, symbol.symIdx, k);
                int unknown = -1;
                int count = 0;
                boolean skip = false;
                for (int index : nb) {
                    if (index < 0 || index >= k) {
                        skip = true;
                        break;
                    }
                    if (!have[index]) {
                        count++;
                        unknown = index;
                    }
                }
                if (skip || count != 1) {
                    continue;
                }
                byte[] value = Arrays.copyOf(symbol.data, Mctak.SYMBOL_LEN);
                for (int index : nb) {
                    if (index != unknown) {
                        Bytes.xor(value, known[index]);
                    }
                }
                if (!have[unknown]) {
                    known[unknown] = value;
                    have[unknown] = true;
                    progress = true;
                }
            }
        }
        for (boolean ok : have) {
            if (!ok) {
                return null;
            }
        }
        byte[] object = new byte[objLen];
        for (int i = 0; i < k; i++) {
            int from = i * Mctak.SYMBOL_LEN;
            if (from >= objLen) {
                break;
            }
            int n = Math.min(Mctak.SYMBOL_LEN, objLen - from);
            System.arraycopy(known[i], 0, object, from, n);
        }
        return object;
    }

    public static List<SymbolIn> copy(List<SymbolIn> symbols) {
        List<SymbolIn> out = new ArrayList<>();
        for (SymbolIn symbol : symbols) {
            out.add(new SymbolIn(symbol.symIdx, symbol.data));
        }
        return out;
    }
}
