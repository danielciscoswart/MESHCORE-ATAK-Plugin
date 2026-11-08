package dev.meshcore.atak.mctak;

/**
 * LoRa airtime used by the governor. CR on the wire is MeshCore's 5..8
 * (4/5..4/8); the formula's CR term is that value minus 4.
 */
public final class Airtime {
    public static final int MESH_OVERHEAD = 13;
    public static final int N_PREAMBLE = 8;

    private Airtime() {}

    public static int packetMs(int datagramLen, int sf, int bwHz, int crWire) {
        if (sf < 5 || sf > 12 || bwHz <= 0 || datagramLen < 0) {
            throw new IllegalArgumentException("radio params");
        }
        double tSym = Math.pow(2, sf) / bwHz;
        double tPre = (N_PREAMBLE + 4.25) * tSym;
        int de = tSym >= 0.0164 ? 1 : 0;
        int cr = crWire >= 5 && crWire <= 8 ? crWire - 4 : crWire;
        int payload = datagramLen + MESH_OVERHEAD;
        double denom = 4.0 * (sf - 2.0 * de);
        double numerator = 8.0 * payload - 4.0 * sf + 28.0 + 16.0;
        int payloadSymbols = 8;
        if (denom > 0) {
            int coded = (int) Math.ceil(numerator / denom) * (cr + 4);
            if (coded > 0) {
                payloadSymbols += coded;
            }
        }
        return (int) Math.round((tPre + payloadSymbols * tSym) * 1000.0);
    }

    /** Duty fraction from frequency in kHz. US 902–928 MHz is a self-imposed 10% cap. */
    public static double duty(int freqKhz) {
        if (freqKhz >= 869_400 && freqKhz <= 869_650) {
            return 0.10;
        }
        if (freqKhz >= 868_000 && freqKhz <= 870_000) {
            return 0.01;
        }
        if (freqKhz >= 902_000 && freqKhz <= 928_000) {
            return 0.10;
        }
        return 0.01;
    }

    public static int bucketCapacityMs(double duty) {
        double seconds = 600.0 * duty;
        if (seconds < 5) {
            seconds = 5;
        }
        if (seconds > 60) {
            seconds = 60;
        }
        return (int) Math.round(seconds * 1000.0);
    }
}
