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

import dev.meshcore.atak.transport.LinkMachine;
import dev.meshcore.atak.transport.LinkRules;
import dev.meshcore.atak.transport.RadioLimits;
import dev.meshcore.atak.transport.RadioSession;

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
 * <p>v7 connect path (one explicit state machine, see {@link LinkMachine}):
 * IDLE, SCAN, CONNECTING, PAIRING, DISCOVER, MTU, NOTIFY, HANDSHAKE, READY or
 * FAILED(reason, status). A bonded {@code MeshCore*} device is tried first and
 * connected directly. Otherwise an unfiltered scan runs, and the link connects
 * on the first match after waiting at most 2 s for a stronger one. If the
 * device is not bonded the link connects first and calls createBond() after the
 * connection is up, then waits for BOND_BONDED (up to 60 s) before discovering
 * services. Stage time limits: connect 20 s, discover 10 s, MTU 5 s,
 * notifications 5 s. Every failure records its stage and the GATT status.
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

    /** Connect early once the stronger-match wait has passed. */
    private final Runnable earlyConnect = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (machine.state() == LinkMachine.State.SCAN && scanning && bestMatch != null && gatt == null) {
                connectTo(bestMatch.device, bestMatch.label, "early", now);
            }
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
    private long firstMatchMs;
    private String targetLabel = "";
    /** Address -> best sighting of the current or most recent scan. */
    private final Map<String, Sighting> seen = new LinkedHashMap<String, Sighting>();
    private Sighting bestMatch;

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
        mainHandler.removeCallbacks(earlyConnect);
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
        // Bonded radio first. Exactly one bonded MeshCore* device: connect to it
        // directly. Several (for example two radios): scan and pick the one that
        // is actually advertising, because a bonded radio that is switched off
        // would otherwise cost a 20 s timeout.
        try {
            List<BluetoothDevice> bonded = bondedMeshCore(adapter);
            if (bonded.size() == 1) {
                BluetoothDevice device = bonded.get(0);
                String name = safeName(device);
                connectTo(device, name.isEmpty() ? "(bonded)" : name, "bonded", nowMs);
                return machine.state() == LinkMachine.State.FAILED ? StartResult.FAILED : StartResult.STARTED;
            }
            if (bonded.size() > 1) {
                machine.note(nowMs, "bonded MeshCore devices=" + bonded.size() + ", scanning to choose", LinkMachine.NO_STATUS);
            }
        } catch (SecurityException ex) {
            machine.note(nowMs, "bonded list denied", LinkMachine.NO_STATUS);
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
        bestMatch = null;
        firstMatchMs = 0L;
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
        mainHandler.removeCallbacks(earlyConnect);
        stopScan();
        closeGatt();
        session.onDisconnected(now);
        bestMatch = null;
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
        if (prior.match() && (bestMatch == null || prior.rssi > bestMatch.rssi)) {
            bestMatch = prior;
        }
        if (prior.match() && firstMatchMs == 0L) {
            // First match: connect after a short wait for a stronger one (R4), not at the end of the window.
            firstMatchMs = System.currentTimeMillis();
            mainHandler.postDelayed(earlyConnect, LinkMachine.EARLY_CONNECT_WAIT_MS);
        }
    }

    @SuppressLint("MissingPermission")
    private void finishScanWindow(long nowMs) {
        stopScan();
        mainHandler.removeCallbacks(earlyConnect);
        if (bestMatch != null && gatt == null) {
            connectTo(bestMatch.device, bestMatch.label, "scan end", nowMs);
            return;
        }
        int n = seen.size();
        String locationNote = n == 0 && appContext != null && "off".equals(locationState(appContext))
                ? " (Location is off)" : "";
        failLink(nowMs, "scan_timeout seen=" + n + (n == 0 ? locationNote : " no MeshCore match"),
                LinkMachine.NO_STATUS);
    }

    // ---- connect -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private void connectTo(BluetoothDevice device, String label, String why, long nowMs) {
        if (device == null || gatt != null) {
            return;
        }
        mainHandler.removeCallbacks(earlyConnect);
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
        mainHandler.removeCallbacks(earlyConnect);
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
                    if (g != gatt || status == BluetoothGatt.GATT_SUCCESS) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    session.engine().counters().inc("gatt_write_status_" + status);
                    machine.note(now, "write callback", status);
                    // C2: a failed write ends the attempt with its real status code (for example
                    // 5, 15 or 137: pairing keys rejected) instead of timing out as "no handshake reply".
                    if (LinkRules.writeFailureEndsAttempt(machine.state())) {
                        failLink(now, "write failed", status);
                    }
                }
            });
        }
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
                session.onNotification(copy, System.currentTimeMillis());
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
            sb.append("  ").append(s.label).append(' ').append(s.rssi).append("dBm uuid=")
                    .append(s.uuid ? "Y" : "n").append(" name=").append(s.nameMatch ? "Y" : "n").append('\n');
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
        sb.append("counters: ").append(session.engine().counters().snapshot()).append('\n');
        return sb.toString();
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
        String address = device.getAddress();
        if (address == null || address.length() < 5) {
            return "";
        }
        return "..." + address.substring(address.length() - 5);
    }
}
