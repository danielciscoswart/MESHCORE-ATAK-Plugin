# Contributing

Thanks for taking a look. This is a small, early project. Small, focused pull requests are easiest to review.

## Never commit SDK files, keys, or builds

The ATAK-CIV SDK is licensed by the TAK Product Center. Its license does not allow copying or publishing it. Do not add any of these to a commit, a pull request, or an issue:

- `main.jar`, `atak-gradle-takdev.jar`, `atak-javadoc.jar`, `mapping.txt`, SDK samples, SDK docs, or an SDK zip.
- `android_keystore`, `*.keystore`, `*.jks`, `*.pem`, `.takdev/`, or `local.properties`.
- APKs or other build output.
- Channel keys, Bluetooth PINs, radio dumps, or real message text.

`.gitignore` blocks most of these, but check `git status` before you commit.

## Do not copy code from other projects

Protocol facts come from [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) at the commit named in the README. The MCTAK layout comes from the published MCTAK v0.1 specification. This repository is Apache-2.0. Do not paste GPL code (for example from the meshcore-TAK or Meshtastic plugins) or ATAK template files into it. If you are unsure, say so in your pull request.

## Run the tests

You need JDK 21 or newer. No Android SDK, ATAK SDK, or radio is needed.

```bash
./scripts/test.sh
```

It must end with `FAILED 0`. Optional checks that need other checkouts print `skipped` and are not counted as passes. Add a test for any behavior change.

Building the plugin APK needs the SDK and is described in `docs/LOCAL_BUILD.md`. Retail ATAK needs a plugin signed through the TAK Third Party Pipeline, so most contributors will not be able to run their own build on a phone. Say in your pull request what you could and could not test.

## Pull requests

- Keep the change small and say what it does and why.
- Keep user-visible wording honest. Do not call a send "delivered". The radio only accepts it.
- Do not log keys, PINs, or message text.
- Do not add claims about range or reliability.

## Reporting bugs

Use the issue templates. For security problems, follow `SECURITY.md` instead of opening an issue.
