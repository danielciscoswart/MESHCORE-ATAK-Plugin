package dev.meshcore.atak.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import dev.meshcore.atak.plugin.PluginHost;

/**
 * v7.4 (J1): receives what ATAK's chat window hands to the MeshCore room's PluginConnector.
 *
 * <p>ATAK 5.6 (ChatManagerMapComponent.sendMessageToDests) calls
 * {@code AtakBroadcast.sendBroadcast(new Intent(connectionString).putExtra("MESSAGE", bundle))}
 * for a contact that has a {@code connector.plugin} connector. The Bundle carries the keys
 * {@code message}, {@code messageId}, {@code conversationId}, {@code senderUid} and
 * {@code destinations}. The receiver only copies those strings out and gives them to the plugin;
 * it never touches the Bundle's other contents and never logs the text.
 */
final class RoomSendReceiver extends BroadcastReceiver {
    /** Receives the extracted fields. Runs on the main thread (LocalBroadcastManager). */
    interface Sink {
        void onRoomSend(String conversationId, String[] destinations, String senderUid,
                        String messageId, String text);

        void onRoomSendUnreadable();
    }

    static final String EXTRA_MESSAGE = "MESSAGE";

    private final Sink sink;

    RoomSendReceiver(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || !AtakChatRoom.CONNECTOR_ACTION.equals(intent.getAction())) {
                return;
            }
            Bundle bundle = intent.getBundleExtra(EXTRA_MESSAGE);
            if (bundle == null) {
                sink.onRoomSendUnreadable();
                return;
            }
            sink.onRoomSend(bundle.getString("conversationId"), PluginHost.destinationsOf(bundle.get("destinations")),
                    bundle.getString("senderUid"), bundle.getString("messageId"),
                    bundle.getString("message"));
        } catch (RuntimeException ex) {
            // A malformed Bundle must never reach ATAK's broadcast thread as an exception.
            try {
                sink.onRoomSendUnreadable();
            } catch (RuntimeException ignored) {
                // Nothing more can be done here.
            }
        }
    }
}
