# Security policy

## Reporting a problem

Please report security problems privately. Do not open a public issue for them.

Use GitHub's private reporting: go to this repository's **Security** tab and choose **Report a vulnerability** (or open <https://github.com/danielciscoswart/MESHCORE-ATAK-Plugin/security/advisories/new>). I will read it and reply as time allows. This is a small hobby project, so there is no response-time guarantee.

## What to include

- The plugin version (the status line in the plugin pane starts with it, for example `v7.5 |`).
- Your ATAK-CIV version, phone model and Android version, and radio firmware version.
- What you did, what you expected, and what happened.

## What not to send

Never paste any of these into an issue, a pull request, a report, or a diagnostic dump, even a private one if you can avoid it:

- Channel keys, QR codes, or share links for a channel.
- Bluetooth PINs.
- Radio dumps or backups, or message text you do not want read.
- Your tak.gov password, tokens, keystores, or any ATAK SDK file.

The COPY DIAG output is designed to leave these out, but read it before you share it.

## Scope

The plugin runs inside ATAK on your phone and talks to your own radio. Things in scope: the plugin's handling of radio frames, key and PIN handling, anything that makes the plugin transmit what you did not type, and anything that leaks keys or message text into logs or diagnostics.

Out of scope: problems in ATAK itself (report them to the TAK Product Center), problems in MeshCore firmware (report them to [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore)), and the fact that anyone who holds a channel key can read and spoof that channel. That limit is documented in the README.
