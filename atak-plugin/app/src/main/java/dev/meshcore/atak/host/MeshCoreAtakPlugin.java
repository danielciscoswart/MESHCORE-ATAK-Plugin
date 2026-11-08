package dev.meshcore.atak.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;

import java.util.List;

import dev.meshcore.atak.ble.MeshcoreGattLink;
import dev.meshcore.atak.engine.Engine;
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
    private Pane pane;
    private TextView status;
    private boolean running;
    private boolean locationOptIn;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            if (locationOptIn) {
                AtakCotBridge.copySelfPosition(host.engine());
                host.engine().beacon(now);
            }
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
                opt.setText(locationOptIn
                        ? pluginContext.getString(dev.meshcore.atak.plugin.R.string.opt_out)
                        : pluginContext.getString(dev.meshcore.atak.plugin.R.string.opt_in));
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
                + " locationOptIn=" + locationOptIn
                + " queued=" + engine.queued()
                + "\nInbound traffic is unverified. Fragments over 165 bytes are not sent."
                + "\n" + radio.block());
    }
}
