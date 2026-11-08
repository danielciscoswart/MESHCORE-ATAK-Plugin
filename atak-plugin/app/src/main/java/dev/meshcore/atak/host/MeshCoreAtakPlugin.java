package dev.meshcore.atak.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
 * ATAK entry point. Lifecycle and toolbar calls match a published ATAK 5.4
 * plugin ({@code LinguaLinkLedger}). They are not a claim that 5.6.0.26 was
 * compiled here. The SDK was not in this environment.
 */
public final class MeshCoreAtakPlugin implements IPlugin {
    private final PluginHost host = new PluginHost();
    private final MeshcoreGattLink radio;
    private final IHostUIService uiService;
    private final Context pluginContext;
    private final ToolbarItem toolbarItem;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExperimentalGeoChatWatch geoChatWatch = new ExperimentalGeoChatWatch();
    private Pane pane;
    private TextView status;
    private boolean running;
    private boolean locationOptIn;
    private boolean watchGeoChat;
    private boolean watchRegistered;
    private String lastChatStatus = "none";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
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
            List<String> events = host.pump(now, now / 1000L);
            for (int i = 0; i < events.size(); i++) {
                AtakCotBridge.publish(events.get(i));
            }
            showStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    public MeshCoreAtakPlugin(IServiceController serviceController) {
        host.onCreate();
        radio = new MeshcoreGattLink(host.session());
        PluginContextProvider contexts = serviceController.getService(PluginContextProvider.class);
        pluginContext = contexts == null ? null : contexts.getPluginContext();
        uiService = serviceController.getService(IHostUIService.class);
        String title = "MeshCore";
        android.graphics.drawable.Drawable icon = null;
        if (pluginContext != null) {
            title = pluginContext.getString(dev.meshcore.atak.plugin.R.string.app_name);
            icon = pluginContext.getResources().getDrawable(dev.meshcore.atak.plugin.R.drawable.ic_meshcore);
        }
        toolbarItem = new ToolbarItem.Builder(
                title,
                MarshalManager.marshal(
                        icon,
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        running = true;
        host.onStart();
        if (uiService != null) {
            uiService.addToolbarItem(toolbarItem);
        }
        handler.post(tick);
    }

    @Override
    public void onStop() {
        running = false;
        handler.removeCallbacks(tick);
        setWatchGeoChat(false);
        radio.stop();
        host.onStop();
        if (uiService != null) {
            uiService.removeToolbarItem(toolbarItem);
        }
    }

    private void showPane() {
        if (pluginContext == null || uiService == null) {
            return;
        }
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
                host.onStart();
                radio.start(pluginContext, System.currentTimeMillis());
                showStatus();
            }
        });
        opt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
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
                radio.stop();
                host.onStop();
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
    }

    private void showStatus() {
        if (status == null) {
            return;
        }
        Engine engine = host.engine();
        status.setText("link " + engine.link()
                + " sessionReady=" + host.session().ready()
                + " channel=" + engine.hasSelectedChannel()
                + " locationOptIn=" + locationOptIn
                + " queued=" + engine.queued()
                + " lastChat=" + lastChatStatus
                + " watchGeoChat=" + watchGeoChat
                + "\nInbound traffic is unverified. Fragments over 165 bytes are not sent."
                + "\nA contact UID is not a private radio destination."
                + "\n" + radio.block());
    }

    private void sendPaneChat(String room, String text) {
        String trimmedRoom = room == null ? "" : room.trim();
        String body = text == null ? "" : text.trim();
        int scope = trimmedRoom.length() == 0 ? Mctak.SCOPE_ALL : Mctak.SCOPE_ROOM;
        Engine.Send send = host.engine().sendChat(scope, 0, trimmedRoom, body, System.currentTimeMillis());
        lastChatStatus = send.status;
        showStatus();
    }

    private void drainWatchedChat(long nowMs) {
        if (!watchGeoChat) {
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
        CommsMapComponent comms = CommsMapComponent.getInstance();
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
