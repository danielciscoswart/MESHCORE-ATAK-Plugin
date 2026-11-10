# Design notes

Short reasons behind four behaviors that look odd and that a future change could undo by accident. They come from the version 0.7.3 to 0.7.5 work and the notes that were removed from `docs/` (still in git history). Each one was found by testing on a real phone and radio, so please keep a test for it if you change it.

## The chat room sends through ATAK's plugin connector Intent

ATAK 5.6's chat window does not hand a typed message to the comms layer for a contact that has a plugin connector. It broadcasts an Intent (the action is the connector's connection string, and the message Bundle is an extra) and stops. In 0.7.3 the room listened for sends through a comms logger hook, which never saw them, so typed text showed in the chat window and went nowhere. Since 0.7.4 the room's connector action is `dev.meshcore.atak.plugin.ROOM_SEND_MESSAGE`, a `RoomSendReceiver` listens for it, and the comms logger only counts and observes. The room has no IP or GeoChat connector, so ATAK has no address to send the message to itself. If the receiver cannot be registered, the room is not offered, because a room nobody listens to would drop messages silently.

## The radio's queue is read only after a channel is chosen

The radio keeps messages it receives while the phone is away in an offline queue (256 messages on the Heltec V4). Each read removes a message from that queue, so the MeshCore app on the same radio can never get it afterward. With no channel chosen, a message could only go to the pane, never to the room. So since 0.7.5 the plugin defers the queue read (counter `drain_deferred`) until the user has chosen a private channel, and then reads the whole queue once. Reading one message per "message waiting" notification, as 0.7.4 did, left the room many messages behind, so a read now continues until the radio answers that there are no more. After every 8 reads, one queued send gets a turn, so typing is not blocked by a large backlog.

## How the duplicate filter decides

Inbound native text is dropped as a repeat when the same key was seen in the last 10 minutes. The key is the channel slot (or the direct sender's prefix), the sender's timestamp, and a hash of the text as the radio delivered it, which includes the sender's name. The filter is a bounded least-recently-used table of 128 keys, and the 10-minute window runs from the first time a key was seen. It only hides true re-deliveries. A message that was sent again by the sender has a new timestamp and shows again. An older check in the room path (same channel, timestamp, sender, and text) and a separate check against chat that ATAK received from the network (a hash of sender and text kept for five minutes) cover other duplicates. Diagnostics list recent timestamps, sizes, and a 16-bit hash of each text (never the text) so a repeat can be diagnosed.

## Replaced rooms become "(closed)" contacts

When the user switches send mode or channel, the room gets a new identity and the old contact would normally be removed. ATAK then looks up each destination of a typed message, finds no contact, sends no Intent, shows "Me:" in the old window, and the plugin cannot say why nothing went out. So a replaced room is kept as a "(closed) MeshCore: ..." contact (same connector, renamed, at most 6) and a message typed into it reaches the plugin, which refuses it with an explanation. ATAK has no public call to close a conversation, and closing through ATAK internals was rejected. All closed contacts are removed when the same room comes back, when the link is lost, when the channel is unselected, and when the plugin stops.
