package dev.meshcore.atak.host;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.maps.MapView;

import dev.meshcore.atak.cot.Cot;
import dev.meshcore.atak.engine.Engine;

/**
 * G1: the one ATAK chat room the plugin offers, "MeshCore: &lt;channel&gt; (&lt;mode&gt;)".
 *
 * <p>The room is an {@link IndividualContact} that has a {@link PluginConnector} and no network
 * connector. ATAK hands a message typed into its window to the connector's Intent action and does
 * nothing else with it (v7.4, J1; read in the 5.6.0.26 bytecode). {@link RoomSendReceiver}
 * receives that Intent and the plugin decides. No copy reaches the TAK server.
 * It exists only while {@link Engine#roomSpec()} says it should: it is replaced when the channel
 * changes and removed when the channel, the radio or the link goes away, and when the plugin
 * stops. A mode switch renames it.
 *
 * <p>Every call here runs on the main thread (the plugin tick or a button).
 */
final class AtakChatRoom {
    /**
     * Intent action of the room's PluginConnector. v7.4 (J1): this is the ONLY way a message typed
     * into the room reaches the plugin. ATAK 5.6's chat send ({@code ChatManagerMapComponent
     * .sendMessageToDests}) finds the {@code connector.plugin} connector on the contact and
     * broadcasts an Intent with this action and a {@code MESSAGE} Bundle through
     * {@code AtakBroadcast} (a LocalBroadcastManager: in-process only). It then skips
     * {@code GeoChatService} and the comms layer completely, so a CommsLogger never sees it.
     * {@link RoomSendReceiver} is registered for this action while the room exists.
     */
    static final String CONNECTOR_ACTION = "dev.meshcore.atak.plugin.ROOM_SEND_MESSAGE";

    private IndividualContact contact;
    private String uid = "";
    private String name = "";

    /** True while a room contact is registered with ATAK. */
    boolean present() {
        return contact != null;
    }

    String uid() {
        return uid;
    }

    String name() {
        return name;
    }

    /**
     * Make ATAK match {@code spec}: null removes the room. Returns true when the set of rooms
     * changed (added or removed), so the caller can register or unregister the comms logger.
     */
    boolean reconcile(Engine.RoomSpec spec) {
        if (spec == null) {
            if (contact == null) {
                return false;
            }
            remove();
            return true;
        }
        if (contact != null && !uid.equals(spec.uid)) {
            remove();
        }
        if (contact == null) {
            IndividualContact created = new IndividualContact(spec.name, spec.uid);
            created.addConnector(new PluginConnector(CONNECTOR_ACTION));
            created.current();
            Contacts.getInstance().addContact(created);
            contact = created;
            uid = spec.uid;
            name = spec.name;
            return true;
        }
        if (!name.equals(spec.name)) {
            contact.setName(spec.name);
            contact.dispatchChangeEvent();
            name = spec.name;
        }
        return false;
    }

    void remove() {
        IndividualContact old = contact;
        contact = null;
        uid = "";
        name = "";
        if (old != null) {
            Contacts.getInstance().removeContact(old);
        }
    }

    /** Open the room's conversation in ATAK's chat window. */
    boolean open() {
        IndividualContact room = contact;
        if (room == null) {
            return false;
        }
        ChatManagerMapComponent chat = ChatManagerMapComponent.getInstance();
        if (chat == null) {
            return false;
        }
        chat.openConversation(room, true);
        return true;
    }

    /**
     * Show one radio message in the room through ATAK's internal dispatcher only (never the
     * external one, so it cannot reach a TAK server). Returns false when there is no room or ATAK
     * refused the event.
     */
    boolean show(Engine.RoomMessage message, long nowSeconds) {
        if (contact == null) {
            return false;
        }
        String selfUid = MapView.getDeviceUid();
        MapView view = MapView.getMapView();
        String selfCallsign = view == null ? "" : view.getDeviceCallsign();
        if (selfUid == null || selfUid.isEmpty()) {
            return false;
        }
        String xml = Cot.roomChat(uid, name, selfUid, selfCallsign == null ? "" : selfCallsign,
                message.senderLabel(), message.text, message.id, nowSeconds);
        return AtakCotBridge.publish(xml);
    }
}
