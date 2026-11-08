package dev.meshcore.atak.mctak;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fragment reassembly for one receiver. Full object buffers are allocated only
 * after two distinct symbols. Voice and file content are counted and not delivered.
 */
public final class Reassembly {
    public interface Delivery {
        void deliver(int sender, int content, byte[] object, long crc);
    }

    private static final class Slot {
        final int sender;
        final int transferId;
        int content;
        int objLen;
        int k;
        boolean paramsFixed;
        final LinkedHashMap<Integer, byte[]> symbols = new LinkedHashMap<>();
        int crcFails;
        long lastNewMs;
        int consecutiveMismatch;

        Slot(int sender, int transferId) {
            this.sender = sender;
            this.transferId = transferId;
        }
    }

    private final Map<String, Slot> active = new LinkedHashMap<>();
    private final Counters counters;
    private final Delivery delivery;

    public Reassembly(Counters counters, Delivery delivery) {
        this.counters = counters;
        this.delivery = delivery;
    }

    public void offer(Mctak.Header header, Mctak.Frag frag, long nowMs) {
        if (frag.content == Mctak.CONTENT_VOICE || frag.content == Mctak.CONTENT_FILE) {
            counters.inc("gated_voice_or_file");
            return;
        }
        String key = key(header.sender, frag.transferId);
        Slot slot = active.get(key);
        if (slot == null) {
            if (active.size() >= 8) {
                counters.inc("reassembly_rejected");
                return;
            }
            int fromSender = 0;
            for (Slot existing : active.values()) {
                if (existing.sender == header.sender) {
                    fromSender++;
                }
            }
            if (fromSender >= 2) {
                counters.inc("reassembly_rejected");
                return;
            }
            slot = new Slot(header.sender, frag.transferId);
            active.put(key, slot);
        }
        if (!slot.paramsFixed) {
            slot.content = frag.content;
            slot.objLen = frag.objLen;
            slot.k = frag.k;
            slot.paramsFixed = true;
        } else if (slot.content != frag.content || slot.objLen != frag.objLen || slot.k != frag.k) {
            slot.consecutiveMismatch++;
            counters.inc("frag_mismatch");
            if (slot.consecutiveMismatch >= 3) {
                active.remove(key);
                counters.inc("transfer_reseed");
            }
            return;
        } else {
            slot.consecutiveMismatch = 0;
        }
        if (slot.symbols.containsKey(frag.symIdx)) {
            counters.inc("dup_symbol");
            return;
        }
        slot.symbols.put(frag.symIdx, frag.symbol);
        slot.lastNewMs = nowMs;
        if (slot.symbols.size() < 2) {
            counters.inc("frag_held");
            return;
        }
        tryDecode(slot);
    }

    public void evictIdle(long nowMs) {
        Iterator<Map.Entry<String, Slot>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Slot slot = it.next().getValue();
            if (nowMs - slot.lastNewMs > 600_000L) {
                it.remove();
                counters.inc("reassembly_idle_evict");
            }
        }
    }

    private void tryDecode(Slot slot) {
        List<Fountain.SymbolIn> symbols = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : slot.symbols.entrySet()) {
            symbols.add(new Fountain.SymbolIn(entry.getKey(), entry.getValue()));
        }
        byte[] object = Fountain.decode(slot.transferId, slot.k, slot.objLen, symbols);
        if (object == null) {
            return;
        }
        if (!Fountain.crcOk(object)) {
            slot.crcFails++;
            counters.inc("crc_fail");
            if (!symbols.isEmpty()) {
                symbols.remove(symbols.size() - 1);
                byte[] retry = Fountain.decode(slot.transferId, slot.k, slot.objLen, symbols);
                if (retry != null && Fountain.crcOk(retry)) {
                    object = retry;
                } else {
                    if (slot.crcFails >= 3) {
                        active.remove(key(slot.sender, slot.transferId));
                        counters.inc("crc_purge");
                    }
                    return;
                }
            } else {
                return;
            }
        }
        long crc = dev.meshcore.atak.util.Bytes.u32le(object, object.length - 4);
        active.remove(key(slot.sender, slot.transferId));
        counters.inc("transfer_decoded");
        delivery.deliver(slot.sender, slot.content, object, crc);
    }

    private static String key(int sender, int transferId) {
        return Integer.toHexString(sender) + ":" + transferId;
    }
}
