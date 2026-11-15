# Linux packages

Pantheon (`khonager/pantheon`) packages the full Android application with an
Android emulator and scrcpy window. `tipstroke` selects the **stable branch**;
`tipstroke-unstable` explicitly selects **unstable**. These are branch channels,
not a claim that the emulator has native Linux tablet input or pressure support.
The limited Compose desktop preview is not used.

The Linux package channels workflow builds and tests both branches on pushes,
reuses existing immutable builds, and publishes signed APKs under
`linux-<channel>-<commit>` releases. Rolling `linux-stable` and `linux-unstable`
releases contain only `tipstroke.json` manifests pointing to those immutable APKs
with SHA-256 hashes. All Linux releases are prereleases so they do not replace
numbered Android releases. Do not delete immutable Linux releases referenced by
Nix lock files. The workflow is present on both branches; pushes refresh both channels.

Pantheon locks the manifests as file inputs. Updating the unstable input with
`nix flake update --refresh pantheon/tipstroke-unstable` obtains the latest
successfully published development build; an ordinary rebuild intentionally
keeps its lock. The personal NixOS rebuild wrapper performs this refresh first.
No update checks, Gradle builds, or APK downloads happen when launching the app.
The emulator image and runtime are supplied by Nix at installation time.

Each channel keeps its own emulator and drawings in the user's XDG data directory.
This also keeps packaged installs separate from `./tipstroke laptop` debug data.
Use Android project export/import to transfer drawings. Closing the window leaves
the emulator running so Android can save normally; `tipstroke --stop` stops it.
The emulator requires x86_64 Linux with usable KVM. Android SDK licensing applies
to the bundled runtime. See Pantheon's README for installation and channel options.
