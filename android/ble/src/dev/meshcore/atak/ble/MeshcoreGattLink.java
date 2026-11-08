package dev.meshcore.atak.ble;

import android.annotation.SuppressLint;
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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;

import dev.meshcore.atak.transport.RadioLimits;
import dev.meshcore.atak.transport.RadioSession;

import java.util.Collections;
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
 * <p>This class is the BLE adapter. It is not an ATAK plugin and it does not
 * start a foreground service. A stopped host must call {@link #stop()} so a
 * locked or backgrounded process is not treated as a live radio session.
 * No handset was attached when this file was written.
 */
@SuppressWarnings("deprecation") // setValue/getValue remain the pre-API-33 callbacks older stacks still invoke
public final class MeshcoreGattLink extends BluetoothGattCallback {
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
    }

    private final RadioSession session;
    private final ScanCallback scans = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            onFound(result);
        }
    };

    private Context appContext;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private BluetoothGattCharacteristic tx;
    private boolean scanning;
    private String block = "idle";

    public MeshcoreGattLink(RadioSession session) {
        this.session = session;
    }

    public String block() {
        return block;
    }

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

    /**
     * Start a filtered scan. Returns false when permission, radio, or scanner
     * state does not allow it. This does not transmit a MeshCore frame.
     */
    @SuppressLint("MissingPermission")
    public boolean start(Context context, long nowMs) {
        PermissionReport report = permissions(context);
        if (!report.canLink(Build.VERSION.SDK_INT)) {
            block = "permission";
            return false;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null || manager.getAdapter() == null || !manager.getAdapter().isEnabled()) {
            block = "adapter";
            return false;
        }
        BluetoothLeScanner found = manager.getAdapter().getBluetoothLeScanner();
        if (found == null) {
            block = "scanner";
            return false;
        }
        this.appContext = context.getApplicationContext();
        this.scanner = found;
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(UUID.fromString(RadioLimits.SERVICE_UUID)))
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scans);
        } catch (SecurityException ex) {
            block = "security";
            return false;
        }
        scanning = true;
        block = "scanning";
        return true;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        session.setWritesEnabled(false);
        stopScan();
        BluetoothGatt open = gatt;
        gatt = null;
        rx = null;
        tx = null;
        if (open != null) {
            try {
                open.disconnect();
                open.close();
            } catch (SecurityException ex) {
                block = "security";
            }
        }
        block = "stopped";
    }

    /** Let the session advance, then hand at most one frame to the RX characteristic. */
    @SuppressLint("MissingPermission")
    public void flush(long nowMs) {
        session.tick(nowMs);
        byte[] frame = session.pollWrite();
        BluetoothGatt open = gatt;
        BluetoothGattCharacteristic sink = rx;
        if (frame == null || open == null || sink == null) {
            return;
        }
        sink.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        sink.setValue(frame);
        try {
            boolean started = open.writeCharacteristic(sink);
            if (!started) {
                block = "write_rejected";
            }
        } catch (SecurityException ex) {
            block = "security";
        }
    }

    @SuppressLint("MissingPermission")
    private void onFound(ScanResult result) {
        if (result == null || result.getDevice() == null || gatt != null) {
            return;
        }
        stopScan();
        BluetoothDevice device = result.getDevice();
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            try {
                device.createBond();
            } catch (SecurityException ex) {
                block = "security";
                return;
            }
            block = "bonding";
        }
        try {
            gatt = device.connectGatt(appContext, false, this, BluetoothDevice.TRANSPORT_LE);
            block = "connecting";
        } catch (SecurityException ex) {
            block = "security";
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        long now = System.currentTimeMillis();
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            session.onConnected(now);
            try {
                gatt.discoverServices();
            } catch (SecurityException ex) {
                block = "security";
            }
            return;
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            session.onDisconnected(now);
            block = "disconnected";
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            block = "discover_failed";
            return;
        }
        BluetoothGattService service = gatt.getService(UUID.fromString(RadioLimits.SERVICE_UUID));
        if (service == null) {
            block = "no_service";
            return;
        }
        rx = service.getCharacteristic(UUID.fromString(RadioLimits.RX_UUID));
        tx = service.getCharacteristic(UUID.fromString(RadioLimits.TX_UUID));
        if (rx == null || tx == null) {
            block = "no_characteristic";
            return;
        }
        try {
            gatt.requestMtu(RadioLimits.REQUEST_MTU);
        } catch (SecurityException ex) {
            block = "security";
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        int negotiated = status == BluetoothGatt.GATT_SUCCESS ? mtu : 23;
        session.onMtu(negotiated, System.currentTimeMillis());
        BluetoothGattCharacteristic notify = tx;
        if (notify == null) {
            return;
        }
        try {
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor descriptor = notify.getDescriptor(UUID.fromString(RadioLimits.CCCD_UUID));
            if (descriptor == null) {
                block = "no_cccd";
                return;
            }
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(descriptor);
        } catch (SecurityException ex) {
            block = "security";
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            session.onNotifications(true);
            block = "notify";
            return;
        }
        block = "cccd_failed";
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (characteristic == null) {
            return;
        }
        byte[] value = characteristic.getValue();
        if (value == null) {
            session.onNotification(new byte[0], System.currentTimeMillis());
            return;
        }
        byte[] copy = new byte[value.length];
        System.arraycopy(value, 0, copy, 0, value.length);
        session.onNotification(copy, System.currentTimeMillis());
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            block = "write_failed";
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
            block = "security";
        }
        scanning = false;
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
