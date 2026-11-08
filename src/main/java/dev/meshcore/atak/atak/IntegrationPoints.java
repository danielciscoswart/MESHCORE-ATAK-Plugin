package dev.meshcore.atak.atak;

/**
 * Names the ATAK integration this core is ready to sit behind.
 * It deliberately does not declare classes in {@code com.atakmap} or {@code com.atak}.
 * Those types ship in the ATAK-CIV SDK, which is not in this tree.
 *
 * <p>Baseline selected from public release notes: ATAK-CIV 5.6.x.
 * meshcore-TAK documents retail loading on 5.6 and newer (a 5.8.0.0 device check
 * is theirs, not ours). The Meshtastic plugin 1.1.42 notes were tested against
 * ATAK 5.6.0.13. This repository has not been loaded into ATAK.
 *
 * <p>The pieces that do not need the SDK already exist and are tested without it:
 * {@code PluginHost} is the lifecycle and the in-memory contact, chat, and marker
 * lists; {@code RadioSession} is the companion command session;
 * {@code MeshcoreGattLink} is the Android GATT client and compiles only against
 * the public Android SDK. A future plugin class would call those from the ATAK
 * lifecycle. It would still:
 * <ul>
 *   <li>read the self marker only after {@code Engine.setLocationOptIn(true)};</li>
 *   <li>drop outbound CoT that already contains {@code __meshcore};</li>
 *   <li>pass each BLE notification into the session as one frame.</li>
 * </ul>
 * Retail ATAK accepts plugins signed through the TAK Product Center pipeline.
 * An SDK debug signature is not a retail install.
 */
public final class IntegrationPoints {
    public static final String ATAK_BASELINE = "ATAK-CIV 5.6.x";
    public static final String SDK_STATUS = "not bundled; tak.gov download is account-gated";

    private IntegrationPoints() {}
}
