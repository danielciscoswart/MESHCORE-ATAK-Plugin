package dev.meshcore.atak.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;

import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.plugin.PluginState;
import dev.meshcore.atak.transport.LinkMachine;
import dev.meshcore.atak.transport.LinkRules;
import dev.meshcore.atak.transport.RadioChoice;
import dev.meshcore.atak.transport.RadioLimits;
import dev.meshcore.atak.transport.RadioSession;
import dev.meshcore.atak.transport.WriteFailureTracker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Android GATT client for an official MeshCore companion radio.
 *
 * <p>The phone writes whole companion frames to the Nordic UART RX
 * characteristic and treats each TX notification as one frame. That matches
 * {@code meshcore-dev/MeshCore} {@code 81dbae592ffad40b4212bc0b37e82b38da08f4bc}:
 * ESP32 {@code onWrite} copies one GATT write into one frame, and the
 * companion document says the same. Frames are not split. The PIN is not
 * read from device-info and is not logged. Characteristic values are not logged.
 *
 * <p>Connect path (one explicit state machine, see {@link LinkMachine}):
 * IDLE, SCAN, CHOOSE, CONNECTING, PAIRING, DISCOVER, MTU, NOTIFY, HANDSHAKE,
 * READY or FAILED(reason, status).
 *
 * <p>v7.2 (E1): the link never picks a radio by itself. The first time, an
 * unfiltered scan runs and every MeshCore match is handed to the host in
 * {@link Listener#onChooseRadio}; the user picks one, and its address is saved.
 * Later connects go straight to the saved address: a still-paired radio is
 * connected by address, an unpaired one is scanned for by that address only.
 * If the device is not bonded the link connects first and calls createBond() after the
 * connection is up, then waits for BOND_BONDED (up to 60 s) before discovering
 * services. Stage time limits: choose 120 s, connect 20 s, discover 10 s, MTU 5 s,
 * notifications 5 s. Every failure records its stage and the GATT status.
 *
 * <p>v7.2 (E4, E5): the next command is written as soon as the previous reply
 * arrives (60 ms gap, never two GATT writes at once); the 1 s host tick is a
 * fallback. A failed write waits up to 2 s for the disconnect callback so its
 * status is the one recorded.
 *
 * <p>Threading: every GATT and scan callback is run on the Android main
 * thread (the handler overload of connectGatt on API 26+, an explicit post on
 * older releases). The host tick runs there too, so link fields need no locks.
 *
 * <p>This class is the BLE adapter. It is not an ATAK plugin and it does not
 * start a foreground service. A stopped host must call {@link #stop()} so a
 * locked or backgrounded process is not treated as a live radio session.
 * No handset was attached when this file was written.
 */
@SuppressWarnings("deprecation") // setValue/getValue remain the pre-API-33 callbacks older stacks still invoke
public final class MeshcoreGattLink {
    public static final String TAG = "MeshCoreAtak";

    public enum StartResult { STARTED, BUSY, ALREADY_READY, FAILED }

    /** Receives UI-relevant events. Always called on the main thread. */
    public interface Listener {
        /** Something changed: refresh buttons and the status line. */
        void onChanged();

        /** A final outcome worth a toast: Ready, Failed: reason, or Lost. */
        void onOutcome(String text);

        /** E1: the scan ended with these matches and no saved radio. Ask the user which one. */
        void onChooseRadio(List<RadioChoice.Candidate> candidates);

        /** E1: the user chose (and the link saved) this radio. The host turns sending off. */
        void onRadioChosen(String address, String name);
    }

    public static final class PermissionReport {
        public final boolean connect;
        public final boolean scan;
        public final boolean legacyLocation;

        PermissionReport(boolean connect, boolean scan, boolean legacyLocation) {
            this.connect = connect;
            this.scan = scan;
            this.legacyLocation = legacyLocation;
        }

        /** API 31+ needs runtime connect and scan. Older releases need location for scan results. */
        public boolean canLink(int sdkInt) {
            if (sdkInt >= 31) {
                return connect && scan;
            }
            return legacyLocation;
        }

        public String detail(int sdkInt) {
            if (sdkInt >= 31) {
                return "btConnect=" + connect + " btScan=" + scan;
            }
            return "fineLocation=" + legacyLocation;
        }
    }

    private static final class Sighting {
        final String key;
        String label;
        int rssi;
        boolean uuid;
        boolean nameMatch;
        BluetoothDevice device;

        Sighting(String key, String label, int rssi, boolean uuid, boolean nameMatch, BluetoothDevice device) {
            this.key = key;
            this.label = label;
            this.rssi = rssi;
            this.uuid = uuid;
            this.nameMatch = nameMatch;
            this.device = device;
        }

        boolean match() {
            return uuid || nameMatch;
        }
    }

    private static final int SEEN_CAP = 200;
    private static final int WRITE_REFUSAL_LIMIT = 5;
    /** How long to wait for onCharacteristicWrite before assuming it was lost. */
    private static final long WRITE_CALLBACK_WAIT_MS = 5_000L;
    private static final UUID SERVICE = UUID.fromString(RadioLimits.SERVICE_UUID);
    private static final ParcelUuid SERVICE_PARCEL = new ParcelUuid(SERVICE);

    private final RadioSession session;
    private final LinkMachine machine = new LinkMachine();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Callback gattCallback = new Callback();
    private Listener listener;

    private final ScanCallback scans = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onSeen(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) {
                return;
            }
            for (ScanResult result : results) {
                onSeen(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (machine.state() == LinkMachine.State.SCAN) {
                failLink(System.currentTimeMillis(), "scan_failed", errorCode);
            }
        }
    };

    /** E4: write the next command once the 60 ms gap allows. Posted after a notification or a completed write. */
    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            stepNow(System.currentTimeMillis());
        }
    };

    /** E5: gives up waiting for the disconnect callback after a failed write. */
    private final Runnable writeFailureCheck = new Runnable() {
        @Override
        public void run() {
            resolveWriteFailureByTimeout(System.currentTimeMillis());
        }
    };

    private Context appContext;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothGattCharacteristic tx;
    private boolean scanning;
    private boolean sawBonding;
    /** When createBond() was called for this attempt, or 0 when it was not. */
    private long bondRequestedMs;
    /** When this plugin last closed its own GATT client, or 0. */
    private long lastCloseMs;
    /** Start times of recent scans, for Android's 5-starts-per-30-s limit. */
    private final List<Long> scanStarts = new ArrayList<Long>();
    private String targetLabel = "";
    /** Address -> best sighting of the current or most recent scan. */
    private final Map<String, Sighting> seen = new LinkedHashMap<String, Sighting>();
    /** E1: when set, this scan accepts only this address (a saved radio that is not paired). */
    private String wantedAddress;
    private PluginState state;
    /** E4: a GATT write was accepted and its onCharacteristicWrite has not arrived. */
    private boolean writeInFlight;
    private long writeSentMs;
    private final WriteFailureTracker writeFailure = new WriteFailureTracker();

    public MeshcoreGattLink(RadioSession session) {
        this.session = session;
        machine.setListener(new LinkMachine.Listener() {
            @Override
            public void onTransition(LinkMachine.Entry entry) {
                Log.i(TAG, "link " + entry.state + " " + entry.detail
                        + (entry.status == LinkMachine.NO_STATUS ? "" : " status=" + entry.status));
                Listener l = listener;
                if (l == null) {
                    return;
                }
                if (entry.state == LinkMachine.State.READY) {
                    l.onOutcome("Ready");
                } else if (entry.state == LinkMachine.State.FAILED) {
                    l.onOutcome(outcomeText());
                }
                l.onChanged();
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Where the chosen radio is remembered. Without it every connect asks again. */
    public void setState(PluginState state) {
        this.state = state;
    }

    public LinkMachine machine() {
        return machine;
    }

    private String outcomeText() {
        String text = machine.failureText();
        String out = machine.failStage() == LinkMachine.State.READY ? "Lost: " + text : "Failed: " + text;
        return out.length() > 70 ? out.substring(0, 70) : out;
    }

    // ---- text for the pane -------------------------------------------------

    /** Compatibility: the one-line summary of the current link state. */
    public String block() {
        return summary(System.currentTimeMillis());
    }

    /** Label for the CONNECT button. */
    public String buttonLabel(long nowMs) {
        return machine.buttonLabel(nowMs);
    }

    /** Whether DISCONNECT should be enabled: something is running, up, or about to auto-retry. */
    public boolean disconnectEnabled() {
        LinkMachine.State s = machine.state();
        return (s != LinkMachine.State.IDLE && s != LinkMachine.State.FAILED) || machine.autoRetry();
    }

    /**
     * Longer advice for the scrolling status area after a failure, or "" when
     * there is none. The one-line status stays short.
     */
    public String advice() {
        if (machine.state() != LinkMachine.State.FAILED) {
            return "";
        }
        if (LinkRules.HELD_REASON.equals(machine.failReason())) {
            return LinkRules.HELD_ADVICE;
        }
        return machine.failStatus() == LinkMachine.NO_STATUS ? "" : LinkRules.advice(machine.failStatus());
    }

    /** Short single-line description of the current state. */
    public String summary(long nowMs) {
        switch (machine.state()) {
            case IDLE:
                return "idle";
            case SCAN:
                return "scanning " + machine.secondsLeft(nowMs, LinkMachine.SCAN_WINDOW_MS)
                        + "s " + summarizeSeen(3);
            case CHOOSE:
                return "choose your radio in the dialog";
            case CONNECTING:
                return "connecting " + targetLabel;
            case PAIRING:
                return "pairing " + targetLabel + ", enter the PIN on the phone if asked";
            case DISCOVER:
                return "finding radio services";
            case MTU:
                return "negotiating MTU";
            case NOTIFY:
                return "enabling notifications";
            case HANDSHAKE:
                return session.handshakeProgress();
            case READY:
                return "ready " + targetLabel + " mtu=" + session.mtu();
            default:
                return "Failed (" + machine.failStage().name().toLowerCase(Locale.US) + "): "
                        + machine.failureText()
                        + (machine.failStage() == LinkMachine.State.SCAN ? " " + summarizeNames(3) : "");
        }
    }

    // ---- permissions -------------------------------------------------------

    public static PermissionReport permissions(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return new PermissionReport(
                    granted(context, "android.permission.BLUETOOTH_CONNECT"),
                    granted(context, "android.permission.BLUETOOTH_SCAN"),
                    true);
        }
        return new PermissionReport(
                true,
                true,
                granted(context, "android.permission.ACCESS_FINE_LOCATION"));
    }

    // ---- start / stop ------------------------------------------------------

    /** True when the 1 s host tick may start a new attempt (only after a READY link was lost). */
    public boolean autoReconnectDue(boolean linkWanted) {
        return machine.autoStartDue(linkWanted, session.reconnectDue(System.currentTimeMillis()));
    }

    /**
     * Begin an attempt. Does nothing to a link that is already in progress or
     * ready (B1): only IDLE or FAILED may start. Re-enables session writes (B2).
     * Does not transmit a MeshCore frame.
     */
    @SuppressLint("MissingPermission")
    public StartResult start(Context context, long nowMs) {
        if (!machine.canStart()) {
            return machine.isReady() ? StartResult.ALREADY_READY : StartResult.BUSY;
        }
        session.setWritesEnabled(true);
        this.appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        mainHandler.removeCallbacks(step);
        writeFailure.clear();
        writeInFlight = false;
        wantedAddress = null;
        if (!machine.enter(LinkMachine.State.SCAN, nowMs, "start", LinkMachine.NO_STATUS)) {
            return StartResult.BUSY;
        }
        PermissionReport report = permissions(context);
        if (!report.canLink(Build.VERSION.SDK_INT)) {
            failLink(nowMs, "no permission: " + report.detail(Build.VERSION.SDK_INT)
                    + " (allow Nearby devices for ATAK)", LinkMachine.NO_STATUS);
            return StartResult.FAILED;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            failLink(nowMs, "bluetooth is off", LinkMachine.NO_STATUS);
            return StartResult.FAILED;
        }
        // E1: a remembered radio is connected by its address. Nothing is picked by name,
        // by signal strength or by "only one paired MeshCore device" any more.
        String saved = state == null ? null : state.savedRadioAddress();
        if (saved != null) {
            BluetoothDevice remembered = null;
            boolean paired = false;
            try {
                remembered = adapter.getRemoteDevice(saved);
                paired = remembered.getBondState() == BluetoothDevice.BOND_BONDED;
            } catch (IllegalArgumentException ex) {
                machine.note(nowMs, "saved radio address rejected by Android", LinkMachine.NO_STATUS);
            } catch (SecurityException ex) {
                machine.note(nowMs, "saved radio not readable", LinkMachine.NO_STATUS);
            }
            String savedName = state.savedRadioName();
            if (savedName.isEmpty() && remembered != null) {
                savedName = safeName(remembered);
            }
            RadioChoice.Plan plan = remembered == null
                    ? RadioChoice.Plan.SCAN_FOR_SAVED : RadioChoice.plan(saved, paired);
            if (plan == RadioChoice.Plan.DIRECT_SAVED) {
                connectTo(remembered, RadioChoice.label(savedName, saved), "saved radio", nowMs);
                return machine.state() == LinkMachine.State.FAILED ? StartResult.FAILED : StartResult.STARTED;
            }
            wantedAddress = saved;
            targetLabel = RadioChoice.label(savedName, saved);
            machine.note(nowMs, "saved radio " + RadioChoice.shortAddress(saved) + " is not paired, scanning for it",
                    LinkMachine.NO_STATUS);
        }
        BluetoothLeScanner found = adapter.getBluetoothLeScanner();
        if (found == null) {
            failLink(nowMs, "no BLE scanner", LinkMachine.NO_STATUS);
            return StartResult.FAILED;
        }
        this.scanner = found;
        // Android allows 5 scan starts per 30 s; beyond that a scan silently returns nothing.
        for (int i = scanStarts.size() - 1; i >= 0; i--) {
            if (nowMs - scanStarts.get(i) >= LinkRules.SCAN_WINDOW_MS) {
                scanStarts.remove(i);
            }
        }
        if (LinkRules.scanLimitHit(scanStarts, nowMs)) {
            failLink(nowMs, LinkRules.SCAN_LIMIT_REASON, LinkMachine.NO_STATUS);
            return StartResult.FAILED;
        }
        scanStarts.add(nowMs);
        seen.clear();
        try {
            scanner.startScan(
                    null,
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scans);
        } catch (SecurityException ex) {
            failLink(nowMs, "scan not allowed", LinkMachine.NO_STATUS);
            return StartResult.FAILED;
        } catch (IllegalArgumentException ex) {
            failLink(nowMs, "scan_bad_args", LinkMachine.NO_STATUS);
            return StartResult.FAILED;
        }
        scanning = true;
        return StartResult.STARTED;
    }

    /** User DISCONNECT or host stop. Always ends in IDLE with every callback ignored afterwards. */
    @SuppressLint("MissingPermission")
    public void stop() {
        long now = System.currentTimeMillis();
        session.setWritesEnabled(false);
        mainHandler.removeCallbacks(step);
        mainHandler.removeCallbacks(writeFailureCheck);
        writeFailure.clear();
        stopScan();
        closeGatt();
        session.onDisconnected(now);
        machine.reset(now, "stopped");
    }

    // ---- 1 s host tick -----------------------------------------------------

    /**
     * Stage timers, bond polling, handshake supervision, then at most one frame
     * to the RX characteristic. Called from the host's 1 s main-thread tick.
     */
    @SuppressLint("MissingPermission")
    public void flush(long nowMs) {
        LinkMachine.State st = machine.state();
        if (st == LinkMachine.State.SCAN && scanning
                && nowMs - machine.stageStartMs() >= LinkMachine.SCAN_WINDOW_MS) {
            finishScanWindow(nowMs);
            st = machine.state();
        }
        if (st == LinkMachine.State.PAIRING) {
            pollBond(nowMs);
            st = machine.state();
        }
        if (writeFailure.expired(nowMs)) {
            resolveWriteFailureByTimeout(nowMs);
            st = machine.state();
        }
        if (machine.checkTimeout(nowMs)) {
            // The machine already recorded the failure; release the radio and scanner.
            cleanupAfterFailure(nowMs);
            st = machine.state();
        }
        if (st == LinkMachine.State.HANDSHAKE) {
            superviseHandshake(nowMs);
            st = machine.state();
        } else if (st == LinkMachine.State.READY
                && session.consecutiveWriteRefusals() >= WRITE_REFUSAL_LIMIT) {
            failLink(nowMs, "Android refused the write " + WRITE_REFUSAL_LIMIT + " times", LinkMachine.NO_STATUS);
            st = machine.state();
        }
        session.tick(nowMs);
        if (st != LinkMachine.State.HANDSHAKE && st != LinkMachine.State.READY) {
            return;
        }
        pumpWrite(nowMs);
        scheduleStep();
    }

    // ---- E4: write the next command as soon as the reply arrives ------------

    /** Ask the session when it could write next and post {@link #step} for then. */
    private void scheduleStep() {
        long delay = session.nextActionDelayMs(System.currentTimeMillis());
        mainHandler.removeCallbacks(step);
        if (delay >= 0L) {
            mainHandler.postDelayed(step, delay);
        }
    }

    private void stepNow(long nowMs) {
        if (gatt == null) {
            return;
        }
        LinkMachine.State st = machine.state();
        if (st == LinkMachine.State.HANDSHAKE) {
            superviseHandshake(nowMs);
            st = machine.state();
        }
        if (st != LinkMachine.State.HANDSHAKE && st != LinkMachine.State.READY) {
            return;
        }
        session.tick(nowMs);
        pumpWrite(nowMs);
    }

    // ---- E5: the real reason a link dies -------------------------------------

    private void resolveWriteFailureByTimeout(long nowMs) {
        WriteFailureTracker.Resolution res = writeFailure.resolveByTimeout(nowMs);
        if (res == null) {
            return;
        }
        machine.note(nowMs, res.detail(), res.finalStatus);
        failLink(nowMs, res.reason, res.finalStatus);
    }

    private void superviseHandshake(long nowMs) {
        if (session.ready()) {
            machine.enter(LinkMachine.State.READY, nowMs, "session ready mtu=" + session.mtu(), LinkMachine.NO_STATUS);
        } else if (session.handshakeMtuBlocked()) {
            failLink(nowMs, "MTU too small (" + session.mtu() + ")", LinkMachine.NO_STATUS);
        } else if (session.handshakeFailed()) {
            failLink(nowMs, "no handshake reply (" + session.handshakeFailedCommand() + ")", LinkMachine.NO_STATUS);
        } else if (session.consecutiveWriteRefusals() >= WRITE_REFUSAL_LIMIT) {
            failLink(nowMs, "Android refused the write " + WRITE_REFUSAL_LIMIT + " times", LinkMachine.NO_STATUS);
        }
    }

    /** B3: the frame leaves the queue only after Android accepts the write. */
    @SuppressLint("MissingPermission")
    private void pumpWrite(long nowMs) {
        if (writeFailure.pending()) {
            return;
        }
        if (writeInFlight) {
            if (nowMs - writeSentMs < WRITE_CALLBACK_WAIT_MS) {
                return;
            }
            // No onCharacteristicWrite in time: do not deadlock, but say so.
            writeInFlight = false;
            session.engine().counters().inc("write_callback_missing");
            machine.note(nowMs, "write callback missing after " + WRITE_CALLBACK_WAIT_MS + " ms", LinkMachine.NO_STATUS);
        }
        byte[] frame = session.peekWrite();
        BluetoothGatt open = gatt;
        BluetoothGattCharacteristic sink = rx;
        if (frame == null || open == null || sink == null) {
            return;
        }
        boolean accepted = false;
        try {
            sink.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            sink.setValue(frame);
            accepted = open.writeCharacteristic(sink);
        } catch (SecurityException ex) {
            machine.note(nowMs, "write denied", LinkMachine.NO_STATUS);
        }
        if (accepted) {
            session.pollWrite();
            session.writeAccepted();
            writeInFlight = true;
            writeSentMs = nowMs;
        } else {
            session.writeRefused();
            machine.note(nowMs, "write refused by Android (frame kept for retry)", LinkMachine.NO_STATUS);
        }
    }

    @SuppressLint("MissingPermission")
    private void pollBond(long nowMs) {
        BluetoothGatt open = gatt;
        if (open == null) {
            return;
        }
        int bond;
        try {
            bond = open.getDevice().getBondState();
        } catch (SecurityException ex) {
            failLink(nowMs, "bond state not readable", LinkMachine.NO_STATUS);
            return;
        }
        if (bond == BluetoothDevice.BOND_BONDED) {
            machine.note(nowMs, "bonded", LinkMachine.NO_STATUS);
            enterDiscover(open, nowMs);
        } else if (bond == BluetoothDevice.BOND_BONDING) {
            sawBonding = true;
        } else if (sawBonding) {
            // Went from BONDING back to NONE: pairing was refused or the PIN was wrong.
            failLink(nowMs, "pairing failed (bond not created)", LinkMachine.NO_STATUS);
        } else if (bondRequestedMs > 0L
                && LinkRules.pairingNeverStarted(false, bond, nowMs - bondRequestedMs)) {
            // Pairing can fail inside one 1 s tick without us ever seeing BONDING.
            failLink(nowMs, "pairing did not start", LinkMachine.NO_STATUS);
        }
    }

    // ---- scan --------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void onSeen(ScanResult result) {
        if (result == null || result.getDevice() == null || gatt != null || !scanning
                || machine.state() != LinkMachine.State.SCAN) {
            return;
        }
        BluetoothDevice device = result.getDevice();
        String address = device.getAddress();
        if (address == null || address.isEmpty()) {
            return;
        }
        String name = resolveName(result, device);
        boolean uuid = hasServiceUuid(result.getScanRecord());
        boolean nameMatch = isMeshCoreName(name);
        int rssi = result.getRssi();
        String label = (name == null || name.isEmpty()) ? "(no-name)" : name;
        Sighting prior = seen.get(address);
        if (prior == null) {
            if (seen.size() >= SEEN_CAP) {
                return;
            }
            prior = new Sighting(address, label, rssi, uuid, nameMatch, device);
            seen.put(address, prior);
        } else {
            if (rssi > prior.rssi) {
                prior.rssi = rssi;
            }
            if (name != null && !name.isEmpty()) {
                prior.label = name;
            }
            prior.uuid = prior.uuid || uuid;
            prior.nameMatch = prior.nameMatch || nameMatch;
            prior.device = device;
        }
        if (wantedAddress != null && RadioChoice.sameAddress(wantedAddress, address)) {
            // A remembered radio that is not paired: this scan accepts only that address.
            connectTo(device, RadioChoice.label(label.equals("(no-name)") ? "" : label, address),
                    "saved radio, scan", System.currentTimeMillis());
        }
    }

    @SuppressLint("MissingPermission")
    private void finishScanWindow(long nowMs) {
        stopScan();
        if (gatt != null) {
            return;
        }
        int n = seen.size();
        if (wantedAddress != null) {
            failLink(nowMs, "saved radio " + RadioChoice.shortAddress(wantedAddress)
                    + " not seen (Forget radio to choose again)", LinkMachine.NO_STATUS);
            return;
        }
        List<RadioChoice.Candidate> matches = candidates();
        if (matches.isEmpty()) {
            String locationNote = n == 0 && appContext != null && "off".equals(locationState(appContext))
                    ? " (Location is off)" : "";
            failLink(nowMs, "scan_timeout seen=" + n + (n == 0 ? locationNote : " no MeshCore match"),
                    LinkMachine.NO_STATUS);
            return;
        }
        // E1: nothing is picked automatically, not even a single match.
        if (!machine.enter(LinkMachine.State.CHOOSE, nowMs, matches.size() + " MeshCore radio(s) found, waiting for the user",
                LinkMachine.NO_STATUS)) {
            return;
        }
        Listener l = listener;
        if (l == null) {
            failLink(nowMs, "no screen to choose a radio", LinkMachine.NO_STATUS);
            return;
        }
        l.onChooseRadio(matches);
    }

    /** Every MeshCore match from the last scan, strongest first, for the dialog. */
    @SuppressLint("MissingPermission")
    private List<RadioChoice.Candidate> candidates() {
        List<RadioChoice.Candidate> out = new ArrayList<RadioChoice.Candidate>();
        for (Sighting s : sortedSeen()) {
            if (s.match()) {
                out.add(new RadioChoice.Candidate(s.key, s.label, s.rssi, isPaired(s.device)));
            }
        }
        return out;
    }

    @SuppressLint("MissingPermission")
    private static boolean isPaired(BluetoothDevice device) {
        try {
            return device != null && device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException ex) {
            return false;
        }
    }

    /**
     * E1: the user picked a radio in the dialog. Saves its address and connects to it.
     * Ignored unless the link is waiting for that choice.
     */
    public void chooseRadio(String address) {
        long now = System.currentTimeMillis();
        if (machine.state() != LinkMachine.State.CHOOSE) {
            return;
        }
        Sighting picked = null;
        for (Sighting sighting : seen.values()) {
            if (RadioChoice.sameAddress(sighting.key, address)) {
                picked = sighting;
            }
        }
        if (picked == null || !picked.match()) {
            failLink(now, "chosen radio no longer in the scan list", LinkMachine.NO_STATUS);
            return;
        }
        String name = picked.label.equals("(no-name)") ? "" : picked.label;
        if (state != null) {
            state.saveRadio(picked.key, name);
        }
        Listener l = listener;
        if (l != null) {
            l.onRadioChosen(picked.key, name);
        }
        connectTo(picked.device, RadioChoice.label(name, picked.key), "chosen by user", now);
    }

    /** E1: the dialog was cancelled. */
    public void cancelChoose() {
        if (machine.state() == LinkMachine.State.CHOOSE) {
            failLink(System.currentTimeMillis(), "radio choice cancelled", LinkMachine.NO_STATUS);
        }
    }

    // ---- connect -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void connectTo(BluetoothDevice device, String label, String why, long nowMs) {
        if (device == null || gatt != null) {
            return;
        }
        stopScan();
        targetLabel = label;
        sawBonding = false;
        bondRequestedMs = 0L;
        if (!machine.enter(LinkMachine.State.CONNECTING, nowMs, "connect " + label + " (" + why + ")",
                LinkMachine.NO_STATUS)) {
            return;
        }
        // C1: if another app on this phone already holds the radio, Android would join that
        // connection and the plugin would talk over the MeshCore app. Refuse instead.
        // UNVERIFIED that this list includes other apps' connections; if it does not, the
        // check finds nothing and the connect proceeds as before.
        if (!LinkRules.ownCloseStillSettling(lastCloseMs, nowMs) && heldByAnotherApp(device)) {
            failLink(nowMs, LinkRules.HELD_REASON, LinkMachine.NO_STATUS);
            return;
        }
        try {
            BluetoothGatt created;
            if (Build.VERSION.SDK_INT >= 26) {
                created = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK, mainHandler);
            } else {
                created = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            }
            if (created == null) {
                failLink(nowMs, "connectGatt returned null", LinkMachine.NO_STATUS);
                return;
            }
            gatt = created;
        } catch (SecurityException ex) {
            failLink(nowMs, "connect not allowed", LinkMachine.NO_STATUS);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean heldByAnotherApp(BluetoothDevice device) {
        try {
            BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager == null) {
                return false;
            }
            List<BluetoothDevice> connected = manager.getConnectedDevices(BluetoothProfile.GATT);
            List<String> addresses = new ArrayList<String>();
            if (connected != null) {
                for (BluetoothDevice other : connected) {
                    addresses.add(other.getAddress());
                }
            }
            return LinkRules.heldByAnotherApp(addresses, device.getAddress());
        } catch (SecurityException ex) {
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void enterDiscover(BluetoothGatt open, long nowMs) {
        if (!machine.enter(LinkMachine.State.DISCOVER, nowMs, "discoverServices", LinkMachine.NO_STATUS)) {
            return;
        }
        boolean started;
        try {
            started = open.discoverServices();
        } catch (SecurityException ex) {
            started = false;
        }
        if (!started) {
            failLink(nowMs, "discoverServices refused", LinkMachine.NO_STATUS);
        }
    }

    @SuppressLint("MissingPermission")
    private void enterNotify(BluetoothGatt open, long nowMs) {
        if (!machine.enter(LinkMachine.State.NOTIFY, nowMs, "enable notifications", LinkMachine.NO_STATUS)) {
            return;
        }
        BluetoothGattCharacteristic notify = tx;
        if (notify == null) {
            failLink(nowMs, "no TX characteristic", LinkMachine.NO_STATUS);
            return;
        }
        try {
            if (!open.setCharacteristicNotification(notify, true)) {
                failLink(nowMs, "setCharacteristicNotification refused", LinkMachine.NO_STATUS);
                return;
            }
            BluetoothGattDescriptor descriptor = notify.getDescriptor(UUID.fromString(RadioLimits.CCCD_UUID));
            if (descriptor == null) {
                failLink(nowMs, "no_cccd", LinkMachine.NO_STATUS);
                return;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!open.writeDescriptor(descriptor)) {
                failLink(nowMs, "writeDescriptor refused", LinkMachine.NO_STATUS);
            }
        } catch (SecurityException ex) {
            failLink(nowMs, "notify not allowed", LinkMachine.NO_STATUS);
        }
    }

    // ---- failure and cleanup ----------------------------------------------

    /** Record FAILED for the running stage, then release scanner, GATT and session. */
    private void failLink(long nowMs, String reason, int status) {
        LinkMachine.State st = machine.state();
        if (st == LinkMachine.State.IDLE || st == LinkMachine.State.FAILED) {
            cleanupAfterFailure(nowMs);
            return;
        }
        machine.fail(nowMs, reason, status);
        cleanupAfterFailure(nowMs);
    }

    @SuppressLint("MissingPermission")
    private void cleanupAfterFailure(long nowMs) {
        mainHandler.removeCallbacks(step);
        mainHandler.removeCallbacks(writeFailureCheck);
        writeFailure.clear();
        writeInFlight = false;
        stopScan();
        closeGatt();
        session.onDisconnected(nowMs);
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        BluetoothGatt open = gatt;
        gatt = null;
        rx = null;
        tx = null;
        if (open == null) {
            return;
        }
        lastCloseMs = System.currentTimeMillis();
        try {
            open.disconnect();
        } catch (SecurityException ignored) {
            // CONNECT permission revoked; close below may also fail.
        }
        try {
            open.close();
        } catch (SecurityException ignored) {
            // nothing more to release
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning || scanner == null) {
            scanning = false;
            return;
        }
        try {
            scanner.stopScan(scans);
        } catch (SecurityException ex) {
            Log.w(TAG, "stopScan denied");
        } catch (IllegalArgumentException ignored) {
            // callback already gone
        }
        scanning = false;
    }

    // ---- GATT callbacks, always handled on the main thread -----------------

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private final class Callback extends BluetoothGattCallback {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    handleConnectionState(g, status, newState);
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    handleServices(g, status);
                }
            });
        }

        @Override
        public void onMtuChanged(final BluetoothGatt g, final int mtu, final int status) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    handleMtu(g, mtu, status);
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt g, final BluetoothGattDescriptor descriptor, final int status) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    handleDescriptorWrite(g, status);
                }
            });
        }

        // API 33+ delivers the value here. Older releases use the two-argument form below.
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            deliverNotification(g, value == null ? new byte[0] : copyOf(value));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null) {
                return;
            }
            byte[] value = characteristic.getValue();
            deliverNotification(g, value == null ? new byte[0] : copyOf(value));
        }

        @Override
        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic characteristic, final int status) {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) {
                        return;
                    }
                    // E4: the previous GATT write is finished; the next one may start.
                    writeInFlight = false;
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        scheduleStep();
                        return;
                    }
                    long now = System.currentTimeMillis();
                    session.engine().counters().inc("gatt_write_status_" + status);
                    machine.note(now, "write callback", status);
                    // C2/E5: a failed write ends the attempt. v7.1 closed the connection at once,
                    // so the disconnect status that explains it was never seen. Wait up to 2 s for
                    // that callback; its status becomes the final one. Record how long the radio
                    // had been silent.
                    if (LinkRules.writeFailureEndsAttempt(machine.state())) {
                        writeFailure.begin(now, status, session.lastNotificationMs());
                        mainHandler.postDelayed(writeFailureCheck, WriteFailureTracker.WAIT_MS);
                    }
                }
            });
        }
    }

    /** Milliseconds since the radio last sent a frame on this link, or -1 when it has not. */
    private long sinceLastNotification(long nowMs) {
        long last = session.lastNotificationMs();
        return last <= 0L ? -1L : Math.max(0L, nowMs - last);
    }

    private static byte[] copyOf(byte[] value) {
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        return copy;
    }

    private void deliverNotification(final BluetoothGatt g, final byte[] copy) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                if (g != gatt) {
                    return;
                }
                long now = System.currentTimeMillis();
                session.onNotification(copy, now);
                // E4: a reply just arrived. Declare READY at once if that finished the
                // handshake, then write the next command when the 60 ms gap allows.
                if (machine.state() == LinkMachine.State.HANDSHAKE) {
                    superviseHandshake(now);
                }
                scheduleStep();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void handleConnectionState(BluetoothGatt g, int status, int newState) {
        long now = System.currentTimeMillis();
        if (g != gatt) {
            // A client we already closed or replaced. Release it and ignore.
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try {
                    g.close();
                } catch (SecurityException ignored) {
                    // nothing to do
                }
            }
            return;
        }
        LinkMachine.State st = machine.state();
        if (writeFailure.pending()
                && (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED)) {
            // E5: the disconnect that explains the failed write. Its status is the final one.
            WriteFailureTracker.Resolution res = writeFailure.resolveByDisconnect(now, status);
            if (res != null) {
                machine.note(now, res.detail(), res.finalStatus);
                failLink(now, res.reason, res.finalStatus);
                return;
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
            machine.note(now, "link ended: " + WriteFailureTracker.notificationText(
                    sinceLastNotification(now)), status);
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failLink(now, st == LinkMachine.State.READY ? "radio disconnected" : "gatt error", status);
            return;
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (st != LinkMachine.State.CONNECTING) {
                machine.note(now, "connected callback in " + st, LinkMachine.NO_STATUS);
                return;
            }
            session.onConnected(now);
            int bond;
            try {
                bond = g.getDevice().getBondState();
            } catch (SecurityException ex) {
                failLink(now, "bond state not readable", LinkMachine.NO_STATUS);
                return;
            }
            if (bond == BluetoothDevice.BOND_BONDED) {
                enterDiscover(g, now);
                return;
            }
            // Connect first, pair second: createBond only once the link is up.
            if (!machine.enter(LinkMachine.State.PAIRING, now, "bond state " + bond, LinkMachine.NO_STATUS)) {
                return;
            }
            if (bond == BluetoothDevice.BOND_BONDING) {
                sawBonding = true;
                return;
            }
            try {
                bondRequestedMs = now;
                if (!g.getDevice().createBond()) {
                    // C4: false can just mean the radio already started pairing. Re-read the state.
                    int again = g.getDevice().getBondState();
                    if (LinkRules.bondRefusalIsFatal(again)) {
                        failLink(now, "createBond refused", LinkMachine.NO_STATUS);
                    } else {
                        sawBonding = again == BluetoothDevice.BOND_BONDING;
                        machine.note(now, "createBond returned false, bond state " + again + ", continuing",
                                LinkMachine.NO_STATUS);
                    }
                }
            } catch (SecurityException ex) {
                failLink(now, "createBond not allowed", LinkMachine.NO_STATUS);
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            String why = st == LinkMachine.State.READY
                    ? "radio disconnected"
                    : "disconnected during " + st.name().toLowerCase(Locale.US);
            failLink(now, why, status);
        }
    }

    @SuppressLint("MissingPermission")
    private void handleServices(BluetoothGatt g, int status) {
        long now = System.currentTimeMillis();
        if (g != gatt || machine.state() != LinkMachine.State.DISCOVER) {
            return;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failLink(now, "discover_failed", status);
            return;
        }
        BluetoothGattService service = g.getService(SERVICE);
        if (service == null) {
            failLink(now, "no_service", LinkMachine.NO_STATUS);
            return;
        }
        rx = service.getCharacteristic(UUID.fromString(RadioLimits.RX_UUID));
        tx = service.getCharacteristic(UUID.fromString(RadioLimits.TX_UUID));
        if (rx == null || tx == null) {
            failLink(now, "no_characteristic", LinkMachine.NO_STATUS);
            return;
        }
        if (!machine.enter(LinkMachine.State.MTU, now, "requestMtu " + RadioLimits.REQUEST_MTU, LinkMachine.NO_STATUS)) {
            return;
        }
        boolean asked;
        try {
            asked = g.requestMtu(RadioLimits.REQUEST_MTU);
        } catch (SecurityException ex) {
            asked = false;
        }
        if (!asked) {
            // C5: the MTU stays at the default, so replies would be cut to 20 bytes.
            machine.note(now, "requestMtu refused", LinkMachine.NO_STATUS);
            session.onMtu(LinkRules.DEFAULT_MTU, now);
            failLink(now, LinkRules.mtuProblem(LinkRules.DEFAULT_MTU), LinkMachine.NO_STATUS);
        }
    }

    private void handleMtu(BluetoothGatt g, int mtu, int status) {
        long now = System.currentTimeMillis();
        if (g != gatt || machine.state() != LinkMachine.State.MTU) {
            return;
        }
        int negotiated = status == BluetoothGatt.GATT_SUCCESS ? mtu : LinkRules.DEFAULT_MTU;
        session.onMtu(negotiated, now);
        // C5: fail visibly when the MTU is still the default 23. At 23 a 19-byte APP_START
        // still fits, but the radio's replies would be truncated to 20 bytes.
        String problem = LinkRules.mtuProblem(negotiated);
        if (problem != null) {
            failLink(now, problem, status == BluetoothGatt.GATT_SUCCESS ? LinkMachine.NO_STATUS : status);
            return;
        }
        enterNotify(g, now);
    }

    private void handleDescriptorWrite(BluetoothGatt g, int status) {
        long now = System.currentTimeMillis();
        if (g != gatt || machine.state() != LinkMachine.State.NOTIFY) {
            return;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failLink(now, "cccd_failed", status);
            return;
        }
        session.onNotifications(true);
        machine.enter(LinkMachine.State.HANDSHAKE, now, "notifications on", LinkMachine.NO_STATUS);
        // E4: start the handshake now, not at the next 1 s tick.
        scheduleStep();
    }

    // ---- scan helpers ------------------------------------------------------

    private String summarizeSeen(int limit) {
        List<Sighting> list = sortedSeen();
        StringBuilder sb = new StringBuilder();
        sb.append("seen=").append(list.size());
        int n = Math.min(limit, list.size());
        for (int i = 0; i < n; i++) {
            Sighting s = list.get(i);
            sb.append(i == 0 ? " [" : "; ");
            sb.append(s.label).append(' ').append(s.rssi);
            if (s.match()) {
                sb.append('*');
            }
        }
        if (n > 0) {
            sb.append(']');
        }
        return sb.toString();
    }

    /** Names and RSSI only, for places where "seen=N" is already in the text. */
    private String summarizeNames(int limit) {
        List<Sighting> list = sortedSeen();
        int n = Math.min(limit, list.size());
        if (n == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            Sighting s = list.get(i);
            sb.append(i == 0 ? "" : "; ").append(s.label).append(' ').append(s.rssi);
            if (s.match()) {
                sb.append('*');
            }
        }
        return sb.append(']').toString();
    }

    private List<Sighting> sortedSeen() {
        List<Sighting> list = new ArrayList<Sighting>(seen.values());
        Collections.sort(list, new Comparator<Sighting>() {
            @Override
            public int compare(Sighting a, Sighting b) {
                return Integer.compare(b.rssi, a.rssi);
            }
        });
        return list;
    }

    private static boolean hasServiceUuid(ScanRecord record) {
        if (record == null) {
            return false;
        }
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid uuid : uuids) {
                if (SERVICE_PARCEL.equals(uuid)) {
                    return true;
                }
            }
        }
        Map<ParcelUuid, byte[]> sparse = record.getServiceData();
        return sparse != null && sparse.containsKey(SERVICE_PARCEL);
    }

    private static boolean isMeshCoreName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.toLowerCase(Locale.US).startsWith("meshcore");
    }

    @SuppressLint("MissingPermission")
    private static String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "" : name.trim();
        } catch (SecurityException ex) {
            return "";
        }
    }

    @SuppressLint("MissingPermission")
    private static List<BluetoothDevice> bondedMeshCore(BluetoothAdapter adapter) {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) {
            return out;
        }
        for (BluetoothDevice device : bonded) {
            if (isMeshCoreName(safeName(device))) {
                out.add(device);
            }
        }
        return out;
    }

    @SuppressLint("MissingPermission")
    private static String resolveName(ScanResult result, BluetoothDevice device) {
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            String fromRecord = record.getDeviceName();
            if (fromRecord != null && !fromRecord.isEmpty()) {
                return fromRecord.trim();
            }
        }
        return safeName(device);
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    // ---- diagnostics (R6) --------------------------------------------------

    /**
     * Link part of the COPY DIAG text. Holds no PIN, key or frame bytes. Bonded
     * device addresses are shortened to their last two bytes.
     */
    @SuppressLint("MissingPermission")
    public String diagnostics(Context context, long nowMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("link state=").append(machine.state());
        if (machine.state() == LinkMachine.State.FAILED) {
            sb.append(" failStage=").append(machine.failStage()).append(" reason=").append(machine.failReason());
            if (machine.failStatus() != LinkMachine.NO_STATUS) {
                sb.append(" gattStatus=").append(machine.failStatus());
            }
        }
        sb.append(" autoRetry=").append(machine.autoRetry()).append('\n');
        String saved = state == null ? null : state.savedRadioAddress();
        sb.append("radio target=").append(targetLabel.isEmpty() ? "(none yet)" : targetLabel)
                .append(" saved=").append(saved == null ? "none"
                        : RadioChoice.label(state.savedRadioName(), saved)).append('\n');
        sb.append(permissions(context).detail(Build.VERSION.SDK_INT)).append('\n');
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        sb.append("bluetooth=").append(adapter == null ? "none" : (adapter.isEnabled() ? "on" : "off"));
        sb.append(" location=").append(locationState(context)).append('\n');
        try {
            if (adapter != null && permissions(context).connect) {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                int count = 0;
                if (bonded != null) {
                    for (BluetoothDevice device : bonded) {
                        String name = safeName(device);
                        if (isMeshCoreName(name)) {
                            count++;
                            sb.append("bonded ").append(name).append(' ').append(shortAddress(device))
                                    .append(" bond=").append(bondName(device.getBondState())).append('\n');
                        }
                    }
                }
                if (count == 0) {
                    sb.append("bonded MeshCore devices: none\n");
                }
            } else {
                sb.append("bonded list: unavailable (no permission or adapter)\n");
            }
        } catch (SecurityException ex) {
            sb.append("bonded list: denied\n");
        }
        try {
            if (manager != null && permissions(context).connect) {
                List<BluetoothDevice> connected = manager.getConnectedDevices(BluetoothProfile.GATT);
                sb.append("gattConnected=").append(connected == null ? 0 : connected.size());
                if (connected != null) {
                    for (BluetoothDevice device : connected) {
                        String name = safeName(device);
                        if (isMeshCoreName(name)) {
                            sb.append(" MeshCore:").append(name).append(' ').append(shortAddress(device));
                        }
                    }
                }
                sb.append(" (unverified: may not list other apps' connections)\n");
            }
        } catch (SecurityException ex) {
            sb.append("gattConnected: denied\n");
        }
        List<Sighting> list = sortedSeen();
        sb.append("last scan seen=").append(list.size()).append('\n');
        int n = Math.min(10, list.size());
        for (int i = 0; i < n; i++) {
            Sighting s = list.get(i);
            sb.append("  ").append(s.label).append(' ').append(RadioChoice.shortAddress(s.key)).append(' ')
                    .append(s.rssi).append("dBm uuid=")
                    .append(s.uuid ? "Y" : "n").append(" name=").append(s.nameMatch ? "Y" : "n")
                    .append(" paired=").append(isPaired(s.device) ? "Y" : "n").append('\n');
        }
        sb.append("stages:\n");
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        for (LinkMachine.Entry entry : machine.history()) {
            sb.append("  ").append(fmt.format(new Date(entry.atMs))).append(' ').append(entry.state).append(' ')
                    .append(entry.detail);
            if (entry.status != LinkMachine.NO_STATUS) {
                sb.append(" status=").append(entry.status);
            }
            sb.append('\n');
        }
        sb.append("session mtu=").append(session.mtu()).append(" connected=").append(session.connected())
                .append(" ready=").append(session.ready()).append(" writes=").append(session.writesEnabled()).append('\n');
        sb.append("session trace:\n");
        for (String line : session.trace()) {
            sb.append("  ").append(line).append('\n');
        }
        appendEngineDiagnostics(sb);
        sb.append("counters: ").append(session.engine().counters().snapshot()).append('\n');
        return sb.toString();
    }

    /**
     * E3, E4, E6: handshake time, the channel table without secrets, per-type frame counts,
     * the radio's own uptime and battery, and the storage state.
     */
    private void appendEngineDiagnostics(StringBuilder sb) {
        Engine engine = session.engine();
        long hs = machine.handshakeMs();
        sb.append("handshake ms=").append(hs < 0L ? "n/a" : Long.toString(hs)).append('\n');
        List<Engine.ChannelRow> rows = engine.channelRows();
        Engine.ChannelRow chosen = engine.selectedChannelRow();
        sb.append("channels: ").append(rows.size()).append(" in use, ").append(engine.emptyChannelSlots())
                .append(" empty slots, selected=")
                .append(chosen == null ? "none" : chosen.index + " fp=" + chosen.fingerprint).append('\n');
        for (Engine.ChannelRow row : rows) {
            sb.append("  [").append(row.index).append("] ").append(row.name).append(" fp=").append(row.fingerprint)
                    .append(row.safe ? " ok" : " refused: " + row.reason).append('\n');
        }
        sb.append("frames: native_text=").append(engine.counters().get("native_text_rx"))
                .append(" mctak_data=").append(engine.counters().get("mctak_data_rx"))
                .append(" no_more_messages=").append(engine.counters().get("no_more_messages")).append('\n');
        sb.append("last native text: ").append(engine.lastNativeNote()).append('\n');
        Engine.RadioStats stats = engine.radioStats();
        if (stats == null) {
            sb.append("radio stats: none read yet (stats_unavailable=")
                    .append(engine.counters().get("stats_unavailable")).append(")\n");
        } else {
            sb.append("radio stats: uptime=").append(stats.uptimeSecs).append("s battery=").append(stats.batteryMv)
                    .append("mV errFlags=0x").append(Integer.toHexString(stats.errFlags))
                    .append(" queue=").append(stats.queueLen)
                    .append(" read ").append(Math.max(0L, (System.currentTimeMillis() - stats.atMs) / 1000L))
                    .append("s ago, readings=").append(engine.radioStatsHistory().size())
                    .append(" rebootSeen=").append(engine.radioRebootSeen()).append('\n');
        }
        if (state != null) {
            sb.append("storage: seqWrites=").append(state.writes()).append(" lastError=")
                    .append(state.lastError().isEmpty() ? "none" : state.lastError()).append('\n');
        }
    }

    private static String locationState(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return "unknown";
            }
            if (Build.VERSION.SDK_INT >= 28) {
                return lm.isLocationEnabled() ? "on" : "off";
            }
            int mode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE, 0);
            return mode != 0 ? "on" : "off";
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }

    private static String bondName(int bond) {
        switch (bond) {
            case BluetoothDevice.BOND_BONDED: return "BONDED";
            case BluetoothDevice.BOND_BONDING: return "BONDING";
            default: return "NONE";
        }
    }

    private static String shortAddress(BluetoothDevice device) {
        return RadioChoice.shortAddress(device.getAddress());
    }
}
