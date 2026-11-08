package dev.meshcore.atak.host;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.coords.GeoPoint;

import dev.meshcore.atak.engine.Engine;

/**
 * Map and CoT calls. The method names were read from the public ATAK-CIV
 * 4.6.0.5 tree, not from the gated 5.6.0.26 SDK. {@code docs/ATAK_API_LOOKUP.md}
 * lists each one. A missing symbol on the local SDK is a failed compile, not a
 * stub.
 */
public final class AtakCotBridge {
    private AtakCotBridge() {}

    /** Internal dispatcher only. External dispatch would forward to a TAK server. */
    public static boolean publish(String cotXml) {
        if (cotXml == null) {
            return false;
        }
        CotEvent event = CotEvent.parse(cotXml);
        if (event == null || !event.isValid()) {
            return false;
        }
        CotDispatcher dispatcher = CotMapComponent.getInternalDispatcher();
        if (dispatcher == null) {
            return false;
        }
        dispatcher.dispatch(event);
        return true;
    }

    /**
     * Copies the ATAK self marker into the engine. The engine still ignores the
     * point unless location opt-in is already on. Radio advert coordinates are
     * not read here.
     */
    public static boolean copySelfPosition(Engine engine) {
        MapView view = MapView.getMapView();
        if (view == null || engine == null) {
            return false;
        }
        Marker self = view.getSelfMarker();
        if (self == null) {
            return false;
        }
        GeoPoint point = self.getPoint();
        if (point == null) {
            return false;
        }
        engine.setSelfPosition(point.getLatitude(), point.getLongitude(), null, null, null, 0xFF);
        return true;
    }
}
