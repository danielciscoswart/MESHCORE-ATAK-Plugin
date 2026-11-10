package dev.meshcore.atak.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;

import java.util.List;

import dev.meshcore.atak.ble.MeshcoreGattLink;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.engine.RoomShowQueue;
import dev.meshcore.atak.engine.SendReasons;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.plugin.KeyValueStore;
import dev.meshcore.atak.plugin.PluginHost;
import dev.meshcore.atak.plugin.PluginState;
import dev.meshcore.atak.transport.LinkMachine;
import dev.meshcore.atak.transport.RadioChoice;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * ATAK entry point. The constructor only keeps the controller, context, UI
 * service, and toolbar item. Radio, files, and {@link PluginHost} start in
 * {@link #onStart()}. A failure there is logged and does not escape to ATAK.
 */
public final class MeshCoreAtakPlugin implements IPlugin {
    static final String TAG = "MeshCoreAtak";
    /** Shown at the start of the status line so the installed build is obvious. */
    static final String BUILD_TAG = "v7.5";

    private final IServiceController serviceController;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context pluginContext;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;
    private PluginHost host;
    private MeshcoreGattLink radio;
    private MeshCoreRoomLogger roomLogger;
    private final AtakChatRoom room = new AtakChatRoom();
    /** R2: radio messages the room could not show yet, retried every tick. */
    private final RoomShowQueue roomShowQueue = new RoomShowQueue();
    private boolean roomLoggerRegistered;
    /** J1: receives the Intent ATAK sends to the room's PluginConnector when the user types. */
    private RoomSendReceiver roomSendReceiver;
    private boolean roomSendReceiverRegistered;
    private long roomRetryAtMs;
    /** v7.5: the logger is optional for the room; retry its registration now and then. */
    private long loggerRetryAtMs;
    private boolean loggerFailureCounted;
    private Engine.RoomSpec roomSpecShown;
    private Pane pane;
    private TextView status;
    private TextView statusLine;
    private Button connectButton;
    private Button disconnectButton;
    private View paneRoot;
    private View paneHeader;
    private View paneScroll;
    private Button optButton;
    private Button sendButton;
    private Button forgetButton;
    private Button openChatButton;
    private Button chooseChannelButton;
    private TextView channelsText;
    private TextView inboxText;
    private TextView chatHint;
    private CheckBox pluginOnlyBox;
    private PluginState state;
    private AlertDialog dialog;
    private boolean running;
    private boolean linkWanted;
    /** True while the code (not the user) changes a checkbox, so its listener does nothing. */
    private boolean syncingViews;
    private String lastChatStatus = "none";
    private String startupError;
    /** E10: the last refusal or result, shown on the status line for a few seconds. */
    private String transientNote = "";
    private long transientUntilMs;
    private String lastSendNote = "none";
    private int shownChatResultSeq;
    private String shownChannels = "";
    private String shownInbox = "";
    private static final long TRANSIENT_MS = 8_000L;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || host == null) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                // E2: no channel is ever chosen for the user. The engine keeps position
                // sending off until one is chosen and confirmed.
                host.engine().setNowSeconds(now / 1000L);
                if (host.engine().locationOptIn()) {
                    AtakCotBridge.copySelfPosition(host.engine());
                    Engine.Send sent = host.engine().beacon(now);
                    if (!sent.why.isEmpty()) {
                        // Status line only: a refused beacon repeats every tick, so no toast.
                        setTransient(sent.why);
                    }
                }
                drainNetworkChat(now);
                if (radio != null) {
                    // Without flush, GATT never writes companion handshake frames.
                    radio.flush(now);
                    // Auto-reconnect only after the user tapped CONNECT (linkWanted)
                    // AND a link that had reached READY was lost. The link refuses
                    // to start while any stage is in progress (B1), and a failed
                    // attempt never retries on its own, so it cannot loop silently.
                    if (radio.autoReconnectDue(linkWanted)) {
                        radio.start(pluginContext, now);
                    }
                }
                String transport = host.engine().transportStatus();
                if (transport != null) {
                    lastChatStatus = transport;
                }
                List<String> events = host.pump(now, now / 1000L);
                reconcileRoom(now);
                showRoomMessages(now);
                drainRoomOutbound(now);
                storeSequence();
                for (int i = 0; i < events.size(); i++) {
                    AtakCotBridge.publish(events.get(i));
                }
                announceIncomingAndResults();
                syncViews();
                showStatus();
            } catch (Throwable t) {
                Log.e(TAG, "tick failed: " + t.getClass().getName());
            }
            if (running) {
                handler.postDelayed(this, 1000L);
            }
        }
    };

    public MeshCoreAtakPlugin(IServiceController serviceController) {
        Log.d(TAG, "constructor");
        this.serviceController = serviceController;
        Log.d(TAG, "controller present=" + (serviceController != null));
        PluginContextProvider contexts = null;
        if (serviceController != null) {
            try {
                contexts = serviceController.getService(PluginContextProvider.class);
                uiService = serviceController.getService(IHostUIService.class);
            } catch (Throwable t) {
                Log.e(TAG, "service lookup failed", t);
            }
        }
        pluginContext = contexts == null ? null : contexts.getPluginContext();
        toolbarItem = buildToolbar();
        Log.d(TAG, "constructor done");
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        try {
            if (host == null) {
                PluginHost created = new PluginHost();
                created.onCreate();
                host = created;
                state = new PluginState(new PrefsStore(), host.engine());
                radio = new MeshcoreGattLink(host.session());
                radio.setState(state);
                radio.setListener(new MeshcoreGattLink.Listener() {
                    @Override
                    public void onChanged() {
                        showStatus();
                    }

                    @Override
                    public void onOutcome(String text) {
                        toast(text);
                    }

                    @Override
                    public void onChooseRadio(List<RadioChoice.Candidate> candidates) {
                        showRadioDialog(candidates);
                    }

                    @Override
                    public void onRadioChosen(String address, String name) {
                        // E2: a different radio means position and chat start over.
                        host.engine().clearChannelSelection("radio chosen");
                        syncViews();
                    }
                });
                roomLogger = new MeshCoreRoomLogger();
                restoreSequence();
                Log.d(TAG, "host created");
            }
            host.onStart();
            running = true;
            startupError = null;
            if (uiService != null && toolbarItem != null) {
                uiService.addToolbarItem(toolbarItem);
                Log.d(TAG, "toolbar added");
            }
            handler.post(tick);
            Log.d(TAG, "onStart ready");
        } catch (Throwable t) {
            running = false;
            startupError = "startup failed";
            Log.e(TAG, "onStart failed", t);
        }
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        running = false;
        linkWanted = false;
        handler.removeCallbacks(tick);
        dismissDialog();
        try {
            removeRoom();
        } catch (Throwable t) {
            Log.e(TAG, "room remove failed", t);
        }
        try {
            storeSequence();
        } catch (Throwable t) {
            Log.e(TAG, "sequence store failed", t);
        }
        if (radio != null) {
            try {
                radio.stop();
            } catch (Throwable t) {
                Log.e(TAG, "radio stop failed", t);
            }
        }
        if (host != null) {
            try {
                host.onStop();
            } catch (Throwable t) {
                Log.e(TAG, "host stop failed", t);
            }
        }
        if (uiService != null && toolbarItem != null) {
            try {
                uiService.removeToolbarItem(toolbarItem);
            } catch (Throwable t) {
                Log.e(TAG, "toolbar remove failed", t);
            }
        }
    }

    private ToolbarItem buildToolbar() {
        String title = "MeshCore";
        if (pluginContext != null) {
            try {
                title = pluginContext.getString(dev.meshcore.atak.plugin.R.string.app_name);
            } catch (Throwable t) {
                Log.e(TAG, "toolbar title failed", t);
            }
        }
        gov.tak.api.commons.graphics.Bitmap icon;
        try {
            icon = loadIcon();
        } catch (Throwable t) {
            Log.e(TAG, "toolbar icon failed", t);
            icon = null;
        }
        if (icon == null) {
            Log.e(TAG, "toolbar icon unavailable");
            return null;
        }
        try {
            return new ToolbarItem.Builder(title, icon)
                    .setListener(new ToolbarItemAdapter() {
                        @Override
                        public void onClick(ToolbarItem item) {
                            showPane();
                        }
                    })
                    .build();
        } catch (Throwable t) {
            Log.e(TAG, "toolbar build failed", t);
            return null;
        }
    }

    /**
     * Decodes the PNG launcher. The published plugin samples pass
     * {@code R.drawable.ic_launcher} through the marshaller. A vector drawable
     * is not used. The marshaller receives an Android bitmap from
     * {@link android.graphics.BitmapFactory}, or a solid fallback bitmap.
     */
    private gov.tak.api.commons.graphics.Bitmap loadIcon() {
        android.graphics.Bitmap decoded = null;
        if (pluginContext != null) {
            try {
                decoded = android.graphics.BitmapFactory.decodeResource(
                        pluginContext.getResources(),
                        dev.meshcore.atak.plugin.R.drawable.ic_launcher);
            } catch (Throwable t) {
                Log.e(TAG, "png decode failed", t);
            }
        }
        gov.tak.api.commons.graphics.Bitmap icon = toTakBitmap(decoded);
        if (icon != null) {
            Log.d(TAG, "toolbar png ready");
            return icon;
        }
        Log.d(TAG, "toolbar png fallback");
        android.graphics.Bitmap solid = android.graphics.Bitmap.createBitmap(
                48, 48, android.graphics.Bitmap.Config.ARGB_8888);
        solid.eraseColor(0xFF1B4F72);
        return toTakBitmap(solid);
    }

    private static gov.tak.api.commons.graphics.Bitmap toTakBitmap(android.graphics.Bitmap source) {
        if (source == null) {
            return null;
        }
        return MarshalManager.marshal(
                source,
                android.graphics.Bitmap.class,
                gov.tak.api.commons.graphics.Bitmap.class);
    }

    private void showPane() {
        Log.d(TAG, "showPane");
        if (pluginContext == null || uiService == null) {
            return;
        }
        if (host == null || radio == null) {
            startupError = startupError == null ? "not started" : startupError;
        }
        try {
            View root = PluginLayoutInflater.inflate(pluginContext,
                    dev.meshcore.atak.plugin.R.layout.meshcore_pane, null);
            status = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_status);
            statusLine = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_status_line);
            paneRoot = root;
            paneHeader = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_header);
            paneScroll = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_scroll);
            Button connect = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_connect);
            Button copyDiag = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_copy_diag);
            connectButton = connect;
            final Button opt = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_opt_in);
            optButton = opt;
            forgetButton = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_forget);
            chooseChannelButton = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_choose_channel);
            channelsText = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_channels);
            inboxText = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_inbox);
            chatHint = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_chat_hint);
            pluginOnlyBox = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_plugin_only);
            shownChannels = "";
            shownInbox = "";
            Button disconnect = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_disconnect);
            disconnectButton = disconnect;
            final EditText room = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_room);
            final EditText outText = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_out_text);
            Button send = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_send);
            sendButton = send;
            openChatButton = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_open_chat);
            connect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (host == null || radio == null) {
                        startupError = startupError == null ? "not started" : startupError;
                        showStatus();
                        return;
                    }
                    host.onStart();
                    linkWanted = true;
                    maybeRequestBlePermissions();
                    MeshcoreGattLink.StartResult result =
                            radio.start(pluginContext, System.currentTimeMillis());
                    Log.i(TAG, "connect tap result=" + result + " " + radio.summary(System.currentTimeMillis()));
                    // Tap acknowledgement. A FAILED start already raised its own
                    // "Failed: reason" toast from the link listener.
                    if (result == MeshcoreGattLink.StartResult.STARTED) {
                        toast("Connecting\u2026 up to ~20 s");
                    } else if (result == MeshcoreGattLink.StartResult.BUSY) {
                        toast("Already connecting");
                    } else if (result == MeshcoreGattLink.StartResult.ALREADY_READY) {
                        toast("Already connected");
                    }
                    showStatus();
                }
            });
            copyDiag.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    copyDiagnostics();
                }
            });
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    togglePosition();
                }
            });
            forgetButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    forgetRadio();
                }
            });
            chooseChannelButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showChannelDialog();
                }
            });
            pluginOnlyBox.setChecked(true);
            pluginOnlyBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    if (syncingViews || host == null) {
                        return;
                    }
                    host.engine().setPluginOnlyChat(checked);
                    toast(checked ? "Chat: ATAK plugin users only"
                            : "Chat: MeshCore channel text, anyone on the channel");
                    syncViews();
                    showStatus();
                }
            });
            send.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (room == null || outText == null) {
                        return;
                    }
                    sendPaneChat(room.getText().toString(), outText.getText().toString());
                }
            });
            openChatButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openRoom();
                }
            });
            disconnect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    linkWanted = false;
                    if (radio != null) {
                        radio.stop();
                    }
                    if (host != null) {
                        host.onStop();
                    }
                    toast("Disconnected");
                    showStatus();
                }
            });
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, Double.valueOf(0.5d))
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, Double.valueOf(1.0d))
                    .build();
            uiService.showPane(pane, null);
            showStatus();
        } catch (Throwable t) {
            Log.e(TAG, "showPane failed", t);
        }
    }

    private void showStatus() {
        long now = System.currentTimeMillis();
        if (host == null || radio == null) {
            String text = startupError == null ? "not started" : startupError;
            if (statusLine != null) {
                statusLine.setText(BUILD_TAG + " | " + text);
            }
            if (status != null) {
                status.setText(text);
            }
            if (disconnectButton != null) {
                disconnectButton.setEnabled(false);
            }
            return;
        }
        // Header first: this is what Daniel sees without scrolling.
        if (connectButton != null) {
            connectButton.setText(radio.buttonLabel(now));
        }
        if (disconnectButton != null) {
            disconnectButton.setEnabled(radio.disconnectEnabled());
        }
        if (statusLine != null) {
            // E10: a recent refusal or send result replaces the link summary for a few seconds.
            boolean note = now < transientUntilMs && !transientNote.isEmpty();
            statusLine.setText(BUILD_TAG + " | " + (note ? transientNote : radio.summary(now)));
        }
        if (status == null) {
            return;
        }
        Engine engine = host.engine();
        MeshcoreGattLink.PermissionReport perms = MeshcoreGattLink.permissions(pluginContext);
        Engine.ChannelRow chosen = engine.selectedChannelRow();
        status.setText("build=" + BUILD_TAG
                + "\nlink=" + engine.link()
                + " ready=" + host.session().ready()
                + " ch=" + (chosen == null ? "none" : chosen.index + " " + chosen.fingerprint)
                + " loc=" + engine.locationOptIn()
                + " q=" + engine.queued()
                + "\nchat mode: " + engine.chatModeLabel()
                + "\nlast chat result: " + (engine.lastChatResult().isEmpty() ? "none" : engine.lastChatResult())
                + "\nlast send note: " + lastSendNote
                + "\ntransport=" + lastChatStatus
                + "\nroom: " + roomSummary()
                + "\nperms " + perms.detail(Build.VERSION.SDK_INT)
                + "\nradio: " + radio.summary(now)
                + (radio.advice().isEmpty() ? "" : "\n" + radio.advice())
                + "\n\nInbound traffic is unverified. Fragments over 165 bytes are not sent."
                + "\nA contact UID is not a private radio destination.");
    }

    /** Context for toasts: ATAK's Activity when available, else the plugin context. */
    private Context toastContext() {
        try {
            MapView mapView = MapView.getMapView();
            if (mapView != null && mapView.getContext() != null) {
                return mapView.getContext();
            }
        } catch (Throwable t) {
            Log.e(TAG, "toast context lookup failed", t);
        }
        return pluginContext;
    }

    /** Short toast on the main thread. Two lines at most (Android 12+ cuts longer text). */
    private void toast(final String text) {
        toast(text, true);
    }

    /** {@code log} is false for received text: it is shown, never written to the log. */
    private void toast(final String text, boolean log) {
        if (log) {
            Log.i(TAG, "toast: " + text);
        }
        final String shown = text.length() > 70 ? text.substring(0, 70) : text;
        Runnable show = new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = toastContext();
                    if (ctx != null) {
                        Toast.makeText(ctx, shown, Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "toast failed", t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            handler.post(show);
        }
    }

    /** COPY DIAG: put the diagnostic text on the clipboard. No PIN, key or frame bytes. */
    private void copyDiagnostics() {
        String text;
        try {
            text = buildDiagnostics();
        } catch (Throwable t) {
            text = "diag failed: " + t.getClass().getName();
            Log.e(TAG, "diag failed", t);
        }
        try {
            Context ctx = toastContext();
            ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("MeshCore ATAK diag", text));
            toast("Diag copied. Paste it into chat.");
        } catch (Throwable t) {
            Log.e(TAG, "clipboard failed", t);
            toast("Copy failed: " + t.getClass().getSimpleName());
        }
    }

    private String buildDiagnostics() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("MeshCore ATAK diag build=").append(BUILD_TAG).append('\n');
        sb.append("android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" model=").append(Build.MODEL).append('\n');
        sb.append("atak=").append(atakVersion()).append('\n');
        sb.append("room: ").append(roomSummary()).append('\n');
        if (host != null) {
            sb.append("room counters: ").append(roomCounters()).append('\n');
        }
        if (radio != null && pluginContext != null) {
            sb.append(radio.diagnostics(pluginContext, now));
        } else {
            sb.append("link: not started\n");
        }
        sb.append(paneMeasurements());
        return sb.toString();
    }

    private String atakVersion() {
        try {
            Context ctx = toastContext();
            String pkg = ctx.getPackageName();
            android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            return pkg + " " + info.versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Pane height against screen height: settles whether ATAK cut the pane off. */
    private String paneMeasurements() {
        try {
            android.util.DisplayMetrics dm = pluginContext.getResources().getDisplayMetrics();
            float d = dm.density;
            return "pane: root=" + dp(paneRoot == null ? 0 : paneRoot.getHeight(), d)
                    + "dp header=" + dp(paneHeader == null ? 0 : paneHeader.getHeight(), d)
                    + "dp scroll=" + dp(paneScroll == null ? 0 : paneScroll.getHeight(), d)
                    + "dp rootWidth=" + dp(paneRoot == null ? 0 : paneRoot.getWidth(), d)
                    + "dp screen=" + dp(dm.widthPixels, d) + "x" + dp(dm.heightPixels, d) + "dp\n";
        } catch (Throwable t) {
            return "pane: not measurable\n";
        }
    }

    private static int dp(int px, float density) {
        return density <= 0f ? px : Math.round(px / density);
    }

    /**
     * ATAK plugins have no Activity of their own. Runtime Nearby-devices grants
     * belong to the ATAK host process UID. Ask the MapActivity when possible;
     * otherwise the status line tells the user to grant it in Settings.
     */
    private void maybeRequestBlePermissions() {
        if (Build.VERSION.SDK_INT < 31 || pluginContext == null) {
            return;
        }
        MeshcoreGattLink.PermissionReport report = MeshcoreGattLink.permissions(pluginContext);
        if (report.canLink(Build.VERSION.SDK_INT)) {
            return;
        }
        Activity activity = findHostActivity();
        if (activity == null) {
            Log.e(TAG, "no host Activity to request BLE permissions");
            return;
        }
        try {
            java.util.ArrayList<String> needed = new java.util.ArrayList<String>();
            if (activity.checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add("android.permission.BLUETOOTH_SCAN");
            }
            if (activity.checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add("android.permission.BLUETOOTH_CONNECT");
            }
            if (needed.isEmpty()) {
                return;
            }
            activity.requestPermissions(needed.toArray(new String[0]), 56012);
            Log.d(TAG, "requested BLE permissions count=" + needed.size());
        } catch (Throwable t) {
            Log.e(TAG, "BLE permission request failed", t);
        }
    }

    private Activity findHostActivity() {
        try {
            MapView mapView = MapView.getMapView();
            if (mapView != null) {
                Context ctx = mapView.getContext();
                if (ctx instanceof Activity) {
                    return (Activity) ctx;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "MapView activity lookup failed", t);
        }
        Context ctx = pluginContext;
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private void restoreSequence() {
        if (state != null) {
            state.restoreSequence();
        }
    }

    private void storeSequence() {
        if (state != null) {
            state.storeSequenceIfChanged();
        }
    }

    /**
     * E3: storage obtained from ATAK's own context. The plugin runs in ATAK's process, so
     * the plugin package's files directory is not writable; ATAK's preferences are.
     */
    private final class PrefsStore implements KeyValueStore {
        private SharedPreferences prefs() throws java.io.IOException {
            Context ctx = null;
            try {
                MapView mapView = MapView.getMapView();
                ctx = mapView == null ? null : mapView.getContext();
            } catch (Throwable t) {
                throw new java.io.IOException("ATAK context lookup failed: " + t.getClass().getSimpleName());
            }
            if (ctx == null) {
                throw new java.io.IOException("no ATAK context");
            }
            return ctx.getSharedPreferences("meshcore_atak_plugin", Context.MODE_PRIVATE);
        }

        @Override
        public String get(String key) {
            try {
                return prefs().getString(key, null);
            } catch (java.io.IOException ex) {
                return null;
            }
        }

        @Override
        public void put(String key, String value) throws java.io.IOException {
            if (!prefs().edit().putString(key, value).commit()) {
                throw new java.io.IOException("preferences commit returned false");
            }
        }

        @Override
        public void remove(String key) throws java.io.IOException {
            if (!prefs().edit().remove(key).commit()) {
                throw new java.io.IOException("preferences commit returned false");
            }
        }
    }

    // ---- E10: every refusal is shown ------------------------------------------

    /** A refusal: toast and status line, never silent. */
    private void refuse(String why) {
        lastSendNote = why;
        setTransient(why);
        toast(why);
        showStatus();
    }

    private void setTransient(String text) {
        transientNote = text;
        transientUntilMs = System.currentTimeMillis() + TRANSIENT_MS;
    }

    /** Why nothing may be sent right now, or null when a radio is ready and a channel is chosen. */
    private String sendBlocker() {
        if (host == null || !host.session().ready()) {
            return SendReasons.NOT_READY;
        }
        if (!host.engine().hasSelectedChannel()) {
            return SendReasons.NO_CHANNEL;
        }
        return null;
    }

    // ---- E2: explicit channel and position confirmation -------------------------

    private static String channelKind(Engine.ChannelRow row) {
        return row.safe ? "Private (encrypted)" : row.reason;
    }

    private void showChannelDialog() {
        if (host == null || !host.session().ready()) {
            refuse(SendReasons.NOT_READY);
            return;
        }
        final List<Engine.ChannelRow> safe = new java.util.ArrayList<Engine.ChannelRow>();
        for (Engine.ChannelRow row : host.engine().channelRows()) {
            if (row.safe) {
                safe.add(row);
            }
        }
        if (safe.isEmpty()) {
            refuse("No private channel on this radio. Add one in the MeshCore app first.");
            return;
        }
        Activity activity = findHostActivity();
        if (activity == null) {
            refuse("Cannot open the channel list: no ATAK screen");
            return;
        }
        String[] items = new String[safe.size()];
        for (int i = 0; i < items.length; i++) {
            Engine.ChannelRow row = safe.get(i);
            items[i] = row.name + "\n" + channelKind(row) + "  fingerprint " + row.fingerprint;
        }
        dismissDialog();
        try {
            dialog = new AlertDialog.Builder(activity)
                    .setTitle("Choose a channel")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            chooseChannel(safe.get(which).index);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "channel dialog failed", t);
            refuse("Cannot open the channel list");
        }
    }

    private void chooseChannel(int index) {
        if (host == null) {
            return;
        }
        Engine.Send result = host.engine().selectChannel(index);
        if (!"OK".equals(result.status)) {
            refuse(result.why.isEmpty() ? "Not selected" : result.why);
        } else {
            Engine.ChannelRow row = host.engine().selectedChannelRow();
            String text = "Channel chosen: " + (row == null ? index : row.name + " " + row.fingerprint);
            lastSendNote = text;
            setTransient(text);
            toast(text);
        }
        syncViews();
        showStatus();
    }

    private void togglePosition() {
        if (host == null) {
            return;
        }
        Engine engine = host.engine();
        if (engine.locationOptIn()) {
            engine.setLocationOptIn(false);
            syncViews();
            showStatus();
            return;
        }
        String why = sendBlocker();
        if (why != null) {
            refuse(why);
            return;
        }
        final Engine.ChannelRow row = engine.selectedChannelRow();
        Activity activity = findHostActivity();
        if (row == null || activity == null) {
            refuse(row == null ? SendReasons.NO_CHANNEL : "Cannot ask for confirmation: no ATAK screen");
            return;
        }
        dismissDialog();
        try {
            dialog = new AlertDialog.Builder(activity)
                    .setTitle("Send my position?")
                    .setMessage("Channel: " + row.name + "\n" + channelKind(row) + ", fingerprint "
                            + row.fingerprint + "\n\nAnyone who has this channel can see where you are. "
                            + "Your position goes out about every 5 minutes until you stop it. "
                            + "Radio transmission has not been field tested.")
                    .setPositiveButton("Send my position", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            if (host != null) {
                                host.engine().setLocationOptIn(true);
                                syncViews();
                                showStatus();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "position dialog failed", t);
            refuse("Cannot ask for confirmation");
        }
    }

    // ---- E1: radio dialog and forget ----------------------------------------------

    private void showRadioDialog(final List<RadioChoice.Candidate> candidates) {
        Activity activity = findHostActivity();
        if (activity == null || radio == null) {
            toast("Cannot list radios: no ATAK screen");
            if (radio != null) {
                radio.cancelChoose();
            }
            return;
        }
        String[] items = new String[candidates.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = candidates.get(i).line();
        }
        dismissDialog();
        try {
            dialog = new AlertDialog.Builder(activity)
                    .setTitle("Choose your MeshCore radio")
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            radio.chooseRadio(candidates.get(which).address);
                        }
                    })
                    .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            radio.cancelChoose();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            radio.cancelChoose();
                        }
                    })
                    .show();
            dialogIsRadioChoice = true;
        } catch (Throwable t) {
            Log.e(TAG, "radio dialog failed", t);
            radio.cancelChoose();
        }
    }

    private void dismissDialog() {
        AlertDialog open = dialog;
        dialog = null;
        dialogIsRadioChoice = false;
        if (open != null) {
            try {
                open.dismiss();
            } catch (Throwable t) {
                Log.e(TAG, "dialog dismiss failed", t);
            }
        }
    }

    /** Forget the remembered radio: disconnect, clear the channel and turn sending off. */
    private void forgetRadio() {
        linkWanted = false;
        if (radio != null) {
            radio.stop();
        }
        if (host != null) {
            host.onStop();
            host.engine().clearChannelSelection("radio forgotten");
        }
        if (state != null) {
            state.forgetRadio();
        }
        toast("Radio forgotten. CONNECT will ask which radio again.");
        syncViews();
        showStatus();
    }

    // ---- views follow the engine ------------------------------------------------------

    /**
     * The engine decides what is on: position sending and the chat mode are turned
     * off or reset when the radio or channel changes. The widgets are made to match.
     */
    private void syncViews() {
        if (host == null) {
            return;
        }
        Engine engine = host.engine();
        syncingViews = true;
        try {
            if (pluginOnlyBox != null && pluginOnlyBox.isChecked() != engine.pluginOnlyChat()) {
                pluginOnlyBox.setChecked(engine.pluginOnlyChat());
            }
        } finally {
            syncingViews = false;
        }
        if (optButton != null && pluginContext != null) {
            optButton.setText(pluginContext.getString(engine.locationOptIn()
                    ? dev.meshcore.atak.plugin.R.string.opt_out
                    : dev.meshcore.atak.plugin.R.string.opt_in));
        }
        if (sendButton != null) {
            sendButton.setText("SEND: " + engine.chatModeLabel());
        }
        if (openChatButton != null) {
            openChatButton.setEnabled(room.present());
            openChatButton.setText(room.present() ? "OPEN CHAT: " + room.name() : "OPEN MESHCORE CHAT (choose a channel first)");
        }
        if (chatHint != null) {
            chatHint.setText(engine.pluginOnlyChat()
                    ? "Plugin users only: binary channel data that only other copies of this ATAK plugin can read. "
                            + "The MeshCore app will NOT show it. \"Sent to radio\" means the radio accepted it, not that anyone received it."
                    : "MeshCore channel text: the official MeshCore app shows it to anyone on the chosen channel, "
                            + "with your radio's name in front. Room name is ignored. \"Sent to radio\" means the radio accepted it.");
        }
        if (dialog != null && radio != null && radio.machine().state() != LinkMachine.State.CHOOSE
                && dialogIsRadioChoice) {
            dismissDialog();
        }
        refreshChannelsText(engine);
        refreshInboxText(engine);
    }

    private boolean dialogIsRadioChoice;

    private void refreshChannelsText(Engine engine) {
        if (channelsText == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("Channels (nothing is chosen for you):");
        List<Engine.ChannelRow> rows = engine.channelRows();
        Engine.ChannelRow chosen = engine.selectedChannelRow();
        if (rows.isEmpty() && engine.emptyChannelSlots() == 0) {
            sb.append("\n  none loaded. CONNECT first.");
        }
        for (Engine.ChannelRow row : rows) {
            sb.append("\n ").append(chosen != null && chosen.index == row.index ? "*" : " ")
                    .append('[').append(row.index).append("] ").append(row.name);
            if (row.safe) {
                sb.append("  Private (encrypted)  fp ").append(row.fingerprint);
            } else {
                sb.append("  refused: ").append(row.reason);
            }
        }
        if (engine.emptyChannelSlots() > 0) {
            sb.append("\n  ").append(engine.emptyChannelSlots()).append(" empty slots");
        }
        sb.append("\nChosen: ").append(chosen == null ? "none"
                : chosen.name + " (" + channelKind(chosen) + ", fp " + chosen.fingerprint + ")");
        String text = sb.toString();
        if (!text.equals(shownChannels)) {
            shownChannels = text;
            channelsText.setText(text);
        }
    }

    private void refreshInboxText(Engine engine) {
        if (inboxText == null) {
            return;
        }
        List<Engine.NativeMessage> inbox = engine.nativeInbox();
        StringBuilder sb = new StringBuilder();
        if (inbox.isEmpty()) {
            sb.append("MeshCore messages (unverified): none yet.");
        } else {
            sb.append("MeshCore messages (unverified), newest first:");
            for (int i = inbox.size() - 1; i >= 0; i--) {
                // display() always starts with the label; a message is never shown without it.
                sb.append('\n').append(inbox.get(i).display());
            }
        }
        String text = sb.toString();
        if (!text.equals(shownInbox)) {
            shownInbox = text;
            inboxText.setText(text);
        }
    }

    /** E8 toasts for new native text, and one toast per chat result from the radio. */
    private void announceIncomingAndResults() {
        if (host == null) {
            return;
        }
        Engine engine = host.engine();
        List<Engine.NativeMessage> fresh = engine.takeNewNativeText();
        int shown = 0;
        for (Engine.NativeMessage message : fresh) {
            if (shown >= 3) {
                break;
            }
            String text = message.text.length() > 44 ? message.text.substring(0, 44) : message.text;
            toast(Engine.NativeMessage.LABEL + ": " + text, false);
            shown++;
        }
        if (fresh.size() > shown) {
            toast("+" + (fresh.size() - shown) + " more MeshCore messages (unverified)", true);
        }
        if (engine.chatResultSeq() != shownChatResultSeq) {
            shownChatResultSeq = engine.chatResultSeq();
            String result = engine.lastChatResult();
            lastSendNote = result;
            setTransient(result);
            toast(result);
        }
    }

    /**
     * SEND button. Mode ON (default): MCTAK channel data, readable only by copies of this
     * plugin. Mode OFF: native MeshCore channel text. Both need a ready radio and a chosen
     * private channel, both refuse text over the cap, and every refusal is shown (E10).
     */
    private void sendPaneChat(String room, String text) {
        String body = text == null ? "" : text.trim();
        String why = sendBlocker();
        if (why != null) {
            refuse(why);
            return;
        }
        if (body.isEmpty()) {
            refuse(SendReasons.EMPTY_TEXT);
            return;
        }
        Engine engine = host.engine();
        String trimmedRoom = room == null ? "" : room.trim();
        long now = System.currentTimeMillis();
        Engine.Send send;
        if (engine.pluginOnlyChat()) {
            int scope = trimmedRoom.length() == 0 ? Mctak.SCOPE_ALL : Mctak.SCOPE_ROOM;
            send = engine.sendChat(scope, 0, trimmedRoom, body, now);
        } else {
            send = engine.sendNativeChannelText(body, now);
        }
        if (!"QUEUED".equals(send.status)) {
            refuse(send.why.isEmpty() ? "Not sent: " + send.status : send.why);
            return;
        }
        String queued = "Queued for the radio (" + engine.chatModeLabel() + ")";
        lastSendNote = queued;
        setTransient(queued);
        showStatus();
    }

    // ---- G1 to G5: the MeshCore chat room ---------------------------------------------------

    private String roomCounters() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Long> e : host.engine().counters().snapshot().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("room_") || k.startsWith("chat_other") || k.startsWith("network_chat")
                    || k.startsWith("push_") || k.startsWith("receipt_") || k.startsWith("native_") || k.equals("cot_loop_dropped")) {
                sb.append(k).append('=').append(e.getValue()).append(' ');
            }
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private String roomSummary() {
        return (room.present() ? "present \"" + room.name() + "\"" : "none")
                + " logger=" + roomLoggerRegistered + " sendReceiver=" + roomSendReceiverRegistered;
    }

    /** Keep ATAK's contact list in step with the engine: one room while a channel is chosen. */
    private void reconcileRoom(long now) {
        if (host == null) {
            return;
        }
        Engine engine = host.engine();
        Engine.RoomSpec spec = host.life() == PluginHost.Life.STARTED ? engine.roomSpec() : null;
        if (spec != null && !room.present() && now < roomRetryAtMs) {
            return;
        }
        try {
            Engine.RoomSpec previous = roomSpecShown;
            if (previous != null && spec != null && !previous.channelKey.equals(spec.channelKey)) {
                // Another channel: messages still waiting belong to the old room.
                roomShowQueue.clear(engine.counters());
            }
            boolean changed = room.reconcile(spec);
            roomSpecShown = spec;
            if (changed && room.present()) {
                if (!setRoomListeners(true)) {
                    // A room that nobody listens to would let a message vanish silently. The send
                    // receiver is what listens (v7.5: the comms logger is not needed for that).
                    room.remove();
                    roomSpecShown = null;
                    roomRetryAtMs = now + 5_000L;
                    engine.counters().inc("room_receiver_unavailable");
                    refuse("MeshCore chat room unavailable: ATAK chat not ready");
                    return;
                }
                // H2: when only the mode changed, say who can read what is typed now.
                String notice = Engine.modeNotice(previous, spec);
                String text = notice != null ? notice : "Chat room ready: " + room.name();
                lastSendNote = text;
                setTransient(text);
            } else if (changed) {
                setRoomListeners(false);
            } else if (room.present() && !roomLoggerRegistered && now >= loggerRetryAtMs) {
                // v7.5: the room works without the logger; try to bring the logger up later.
                loggerRetryAtMs = now + 10_000L;
                setRoomListeners(true);
            }
        } catch (Throwable t) {
            Log.e(TAG, "room reconcile failed: " + t.getClass().getName());
            engine.counters().inc("room_reconcile_failed");
            roomSpecShown = null;
            try {
                room.remove();
                setRoomListeners(false);
            } catch (Throwable ignored) {
                Log.e(TAG, "room cleanup failed");
            }
            roomRetryAtMs = now + 5_000L;
        }
    }

    /** Take the room and its listeners down. Safe to call twice. */
    private void removeRoom() {
        roomSpecShown = null;
        try {
            room.remove();
        } finally {
            setRoomListeners(false);
        }
    }

    /**
     * Register or unregister what the room needs. v7.5 (review of v7.4, item 5): the room is offered
     * on the strength of the send receiver alone. The comms logger only feeds the duplicate check
     * for chats ATAK received from the TAK server (G4) and counts other chats; it is registered
     * when ATAK comms are available and its absence only turns that check off (counter
     * room_logger_unavailable, "logger=false" in the diag). It never gates the room.
     */
    private boolean setRoomListeners(boolean want) {
        CommsMapComponent comms = null;
        try {
            comms = CommsMapComponent.getInstance();
        } catch (Throwable t) {
            Log.e(TAG, "comms lookup failed: " + t.getClass().getName());
        }
        if (want) {
            // J1: the receiver is what makes typing in the room work. Without it the room is not offered.
            if (!registerSendReceiver()) {
                return false;
            }
            if (!roomLoggerRegistered && roomLogger != null) {
                boolean failed = comms == null;
                if (!failed) {
                    try {
                        comms.registerCommsLogger(roomLogger);
                        roomLoggerRegistered = true;
                    } catch (Throwable t) {
                        Log.e(TAG, "comms logger register failed: " + t.getClass().getName());
                        failed = true;
                    }
                }
                if (failed && host != null && !loggerFailureCounted) {
                    loggerFailureCounted = true;
                    host.engine().counters().inc("room_logger_unavailable");
                }
            }
            return true;
        }
        unregisterSendReceiver();
        if (roomLoggerRegistered && comms != null && roomLogger != null) {
            try {
                comms.unregisterCommsLogger(roomLogger);
            } catch (Throwable t) {
                Log.e(TAG, "comms logger unregister failed: " + t.getClass().getName());
            }
        }
        roomLoggerRegistered = false;
        if (roomLogger != null) {
            roomLogger.dispose();
        }
        return true;
    }

    private boolean registerSendReceiver() {
        if (roomSendReceiverRegistered) {
            return true;
        }
        try {
            if (roomSendReceiver == null) {
                roomSendReceiver = new RoomSendReceiver(new RoomSendReceiver.Sink() {
                    @Override
                    public void onRoomSend(String conversationId, String[] destinations, String senderUid,
                                           String messageId, String text) {
                        handleRoomSend(conversationId, destinations, senderUid, messageId, text);
                    }

                    @Override
                    public void onRoomSendUnreadable() {
                        handleRoomSend(null, null, null, null, null);
                    }
                });
            }
            AtakBroadcast.DocumentedIntentFilter filter = new AtakBroadcast.DocumentedIntentFilter();
            filter.addAction(AtakChatRoom.CONNECTOR_ACTION,
                    "A message typed into the MeshCore chat room (MeshCore ATAK plugin)");
            AtakBroadcast.getInstance().registerReceiver(roomSendReceiver, filter);
            roomSendReceiverRegistered = true;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "room send receiver failed: " + t.getClass().getName());
            return false;
        }
    }

    private void unregisterSendReceiver() {
        if (!roomSendReceiverRegistered || roomSendReceiver == null) {
            roomSendReceiverRegistered = false;
            return;
        }
        try {
            AtakBroadcast.getInstance().unregisterReceiver(roomSendReceiver);
        } catch (Throwable t) {
            Log.e(TAG, "room send receiver unregister failed: " + t.getClass().getName());
        }
        roomSendReceiverRegistered = false;
    }

    /** Chats ATAK received from the network, handed to the engine for the duplicate check (G4). */
    private void drainNetworkChat(long now) {
        if (host == null || roomLogger == null) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            MeshCoreRoomLogger.NetworkChat chat = roomLogger.pollNetwork();
            if (chat == null) {
                return;
            }
            host.engine().noteNetworkChat(chat.callsign, chat.text, now);
        }
    }

    /** Radio messages for the room, shown through ATAK's internal dispatcher (G3). */
    private void showRoomMessages(long now) {
        if (host == null) {
            return;
        }
        Engine engine = host.engine();
        if (!engine.hasSelectedChannel()) {
            // The room is gone with the channel or the link: what is held belonged to it.
            roomShowQueue.clear(engine.counters());
        }
        final long nowSeconds = now / 1000L;
        // R2: a message ATAK cannot show yet stays queued and is tried again next tick.
        roomShowQueue.pump(host.takeRoomMessages(), now, new RoomShowQueue.Shower() {
            @Override
            public boolean show(Engine.RoomMessage message) {
                try {
                    return room.show(message, nowSeconds);
                } catch (Throwable t) {
                    Log.e(TAG, "room show failed: " + t.getClass().getName());
                    return false;
                }
            }
        }, engine.counters());
    }

    /**
     * Messages typed into the room (G2, J1). ATAK delivers them to {@link RoomSendReceiver}, which
     * calls this on the main thread. The engine sends only what was addressed to the current room.
     * Every refusal and every radio result is shown as a toast and on the status line (G5);
     * nothing is called delivered. A null conversationId means ATAK's Intent could not be read.
     */
    private void handleRoomSend(String conversationId, String[] destinations, String senderUid,
                                String messageId, String text) {
        if (host == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String status = host.offerRoomChat(conversationId, destinations, MapView.getDeviceUid(), messageId,
                text, now);
        lastChatStatus = status;
        if ("QUEUED".equals(status)) {
            String queued = "Queued for the radio (" + host.engine().chatModeLabel() + ")";
            lastSendNote = queued;
            setTransient(queued);
            toast(queued);
        } else if (!host.lastWhy().isEmpty()) {
            refuse(host.lastWhy());
        }
    }

    /** Counters for room sends seen on the comms layer, which should never happen (J1). */
    private void drainRoomOutbound(long now) {
        if (host == null || roomLogger == null) {
            return;
        }
        int ignored = roomLogger.takeIgnored();
        for (int i = 0; i < ignored && i < 50; i++) {
            host.engine().counters().inc("chat_other_ignored");
        }
        int unexpected = roomLogger.takeUnexpectedRoomLogSend();
        for (int i = 0; i < unexpected && i < 50; i++) {
            host.engine().counters().inc("room_logsend_unexpected");
        }
    }

    private void openRoom() {
        try {
            if (!room.present()) {
                refuse(host == null || !host.session().ready() ? SendReasons.NOT_READY : SendReasons.NO_CHANNEL);
                return;
            }
            if (!room.open()) {
                refuse("Cannot open the MeshCore chat: ATAK chat is not ready");
            }
        } catch (Throwable t) {
            Log.e(TAG, "open room failed: " + t.getClass().getName());
            refuse("Cannot open the MeshCore chat");
        }
    }
}
