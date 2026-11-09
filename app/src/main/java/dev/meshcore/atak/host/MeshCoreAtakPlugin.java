package dev.meshcore.atak.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.comms.CommsMapComponent;

import java.util.List;

import dev.meshcore.atak.ble.MeshcoreGattLink;
import dev.meshcore.atak.engine.Engine;
import dev.meshcore.atak.mctak.Mctak;
import dev.meshcore.atak.plugin.PluginHost;
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

    private final IServiceController serviceController;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context pluginContext;
    private IHostUIService uiService;
    private ToolbarItem toolbarItem;
    private PluginHost host;
    private MeshcoreGattLink radio;
    private ExperimentalGeoChatWatch geoChatWatch;
    private Pane pane;
    private TextView status;
    private boolean running;
    private boolean locationOptIn;
    private boolean watchGeoChat;
    private boolean watchRegistered;
    private String lastChatStatus = "none";
    private String startupError;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || host == null) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                if (watchGeoChat && !watchRegistered) {
                    setWatchGeoChat(true);
                }
                if (host.session().ready() && !host.engine().hasSelectedChannel()) {
                    host.engine().selectFirstTeamChannel();
                }
                if (locationOptIn) {
                    AtakCotBridge.copySelfPosition(host.engine());
                    host.engine().beacon(now);
                }
                drainWatchedChat(now);
                String transport = host.engine().transportStatus();
                if (transport != null) {
                    lastChatStatus = transport;
                }
                List<String> events = host.pump(now, now / 1000L);
                storeSequence();
                for (int i = 0; i < events.size(); i++) {
                    AtakCotBridge.publish(events.get(i));
                }
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
                radio = new MeshcoreGattLink(host.session());
                geoChatWatch = new ExperimentalGeoChatWatch();
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
        handler.removeCallbacks(tick);
        try {
            setWatchGeoChat(false);
        } catch (Throwable t) {
            Log.e(TAG, "watch stop failed", t);
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
            Button connect = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_connect);
            Button opt = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_opt_in);
            Button disconnect = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_disconnect);
            final EditText room = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_room);
            final EditText outText = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_out_text);
            Button send = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_send);
            CheckBox watch = root.findViewById(dev.meshcore.atak.plugin.R.id.meshcore_watch);
            connect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (host == null || radio == null) {
                        return;
                    }
                    host.onStart();
                    radio.start(pluginContext, System.currentTimeMillis());
                    showStatus();
                }
            });
            opt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (host == null) {
                        return;
                    }
                    locationOptIn = !locationOptIn;
                    host.engine().setLocationOptIn(locationOptIn);
                    String label = pluginContext.getString(locationOptIn
                            ? dev.meshcore.atak.plugin.R.string.opt_out
                            : dev.meshcore.atak.plugin.R.string.opt_in);
                    opt.setText(label);
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
            watch.setChecked(false);
            watch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    setWatchGeoChat(checked);
                    showStatus();
                }
            });
            disconnect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (radio != null) {
                        radio.stop();
                    }
                    if (host != null) {
                        host.onStop();
                    }
                    showStatus();
                }
            });
            pane = new PaneBuilder(root)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, Double.valueOf(0.5d))
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, Double.valueOf(0.5d))
                    .build();
            uiService.showPane(pane, null);
            showStatus();
        } catch (Throwable t) {
            Log.e(TAG, "showPane failed", t);
        }
    }

    private void showStatus() {
        if (status == null) {
            return;
        }
        if (host == null || radio == null) {
            status.setText(startupError == null ? "not started" : startupError);
            return;
        }
        Engine engine = host.engine();
        String block = radio.block();
        status.setText("link " + engine.link()
                + " sessionReady=" + host.session().ready()
                + " channel=" + engine.hasSelectedChannel()
                + " locationOptIn=" + locationOptIn
                + " queued=" + engine.queued()
                + " lastChat=" + lastChatStatus
                + " watchGeoChat=" + watchGeoChat
                + "\nInbound traffic is unverified. Fragments over 165 bytes are not sent."
                + "\nA contact UID is not a private radio destination."
                + "\n" + block);
    }

    private void restoreSequence() {
        if (pluginContext == null || host == null) {
            return;
        }
        java.io.File file = new java.io.File(pluginContext.getFilesDir(), "mctak-seq.txt");
        if (!file.isFile()) {
            return;
        }
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line = reader.readLine();
            if (line != null && line.trim().length() > 0 && line.trim().length() <= 8) {
                host.engine().resumeAfter(Integer.parseInt(line.trim()));
            }
        } catch (Exception ex) {
            host.engine().counters().inc("sequence_restore_failed");
            Log.e(TAG, "sequence restore failed: " + ex.getClass().getName());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {
                    host.engine().counters().inc("sequence_restore_failed");
                }
            }
        }
    }

    private void storeSequence() {
        if (pluginContext == null || host == null) {
            return;
        }
        java.io.File file = new java.io.File(pluginContext.getFilesDir(), "mctak-seq.txt");
        java.io.FileWriter writer = null;
        try {
            writer = new java.io.FileWriter(file, false);
            writer.write(Integer.toString(host.engine().lastIssuedSequence()));
        } catch (Exception ex) {
            host.engine().counters().inc("sequence_store_failed");
            Log.e(TAG, "sequence store failed: " + ex.getClass().getName());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (java.io.IOException ignored) {
                    host.engine().counters().inc("sequence_store_failed");
                }
            }
        }
    }

    private void sendPaneChat(String room, String text) {
        if (host == null) {
            lastChatStatus = "not started";
            showStatus();
            return;
        }
        String trimmedRoom = room == null ? "" : room.trim();
        String body = text == null ? "" : text.trim();
        int scope = trimmedRoom.length() == 0 ? Mctak.SCOPE_ALL : Mctak.SCOPE_ROOM;
        Engine.Send send = host.engine().sendChat(scope, 0, trimmedRoom, body, System.currentTimeMillis());
        lastChatStatus = send.status;
        showStatus();
    }

    private void drainWatchedChat(long nowMs) {
        if (!watchGeoChat || host == null || geoChatWatch == null) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            String xml = geoChatWatch.poll();
            if (xml == null) {
                return;
            }
            lastChatStatus = host.offerOutboundCot(xml, nowMs);
        }
    }

    private void setWatchGeoChat(boolean enabled) {
        watchGeoChat = enabled;
        if (geoChatWatch == null) {
            return;
        }
        CommsMapComponent comms = null;
        try {
            comms = CommsMapComponent.getInstance();
        } catch (Throwable t) {
            Log.e(TAG, "comms lookup failed", t);
        }
        if (!enabled) {
            if (watchRegistered && comms != null) {
                comms.unregisterCommsLogger(geoChatWatch);
            }
            watchRegistered = false;
            geoChatWatch.dispose();
            return;
        }
        if (comms == null) {
            watchRegistered = false;
            lastChatStatus = "WATCH_UNAVAILABLE";
            return;
        }
        if (!watchRegistered) {
            comms.registerCommsLogger(geoChatWatch);
            watchRegistered = true;
        }
    }
}
