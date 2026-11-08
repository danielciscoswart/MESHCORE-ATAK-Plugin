# REA actions

Tool: [morluto/rea](https://github.com/morluto/rea). Local clone `/tmp/refs/rea`, commit `c5ed046d6e7000b27a12c01a1444f28cc1651c4a`, 2026-11-08. License MIT.

## Executed

1. `git clone --depth 1 https://github.com/morluto/rea.git /tmp/refs/rea`
2. Read `README.md` (the "How REA works" description: the agent asks about a local target, REA returns evidence, the agent does not treat the tool as authority).
3. Read `.agents/skills/reverse-engineer-anything/SKILL.md`, metadata version 61.

The skill's own rule, quoted from that file: use REA when a claim depends on a shipped binary, decompilation, or behavior that source does not establish. For ordinary analysis of a complete source repository, do not run REA readiness or provider commands.

## Not executed

- No `npx rea`, `rea doctor`, `rea setup`, or `rea-agents` command.
- No Ghidra, Hopper, IDA, JADX, or other decompiler.
- No `open_binary`, Android package inspection, firmware extraction, or process capture.
- No ATAK APK or plugin binary was present, and none was downloaded.
- REA's skill text was not treated as permission to ignore the task limits. In particular it was not used to bypass ATAK signing or to recover a channel key.

## This continuation, 2026-11-08

The same REA checkout was still at `c5ed046d6e7000b27a12c01a1444f28cc1651c4a`. The skill file `.agents/skills/reverse-engineer-anything/SKILL.md` was read again (metadata version 61). Its rule is unchanged: use REA when a claim depends on a shipped binary, decompilation, or behavior source does not establish; for a complete source repository, do not run REA readiness or provider commands.

No `npx rea`, `rea doctor`, decompiler, APK, or firmware image was run. The owner identified `meshcore-dev/MeshCore` as the official firmware and protocol source, and that source is public. The payload, framing, channel, and pairing questions are answered there. REA was not used to bypass ATAK signing or to recover a channel key.

## Why that was the whole REA pass

The questions that decide this plugin are answered by public source:

- MeshCore companion frames and the 165-byte radio limit are in the MIT firmware and `docs/companion_protocol.md`.
- MCTAK field layouts are in the published `payload-spec.md`, which says it is enough for a clean-room implementation.
- Meshtastic plugin behavior used for the parity table is in that project's README and the Meshtastic docs page.

Those are source and specification reads. They are not REA runs. A later binary check would be appropriate only for a specific shipped artifact whose behavior the sources do not settle, and it would still not include ATAK signature bypass.
