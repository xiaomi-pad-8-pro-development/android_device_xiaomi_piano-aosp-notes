# AOSP Notes for the Xiaomi Pad 8 Pro (piano)

A clean-room, offline notes app for AOSP-based Android. This is a separate app
from Xiaomi Notes and does not read or modify Xiaomi's private database.

## First slice

- Local text notes and freehand ink notes
- Debounced text and ink autosave while editing, plus immediate save when leaving the editor
- Save open-note data to SQLite before Activity state capture and restore the editor by note ID
- Recreate the Activity normally on rotation and restore the open note from SQLite
- Save and reopen notes on device
- Delete notes with a confirmation step
- Stylus pressure-aware drawing through Android `MotionEvent`
- SQLite storage owned by this app
- Database upgrades fail closed until a preserving schema migration is implemented
- Android Auto Backup rules for the app database, including cloud backup and device transfer
- In-app guidance to Android privacy/backup settings and restore requirements
- User-initiated ZIP export/import through Android's document picker

Continuous in-app cloud sync, Xiaomi accounts, AI, audio recording, handwriting
recognition, stock Xiaomi data import and exact HyperOS UI parity are out of
scope for this initial slice. Android Auto Backup and device transfer are
supported through the OS backup framework. The ink format is app-owned JSON
and is not compatible with Xiaomi's `mind_content` payload.

## Build integration

This tree builds the `PianoAospNotes` Soong app. Sync it to
`device/xiaomi/piano-aosp-notes`; copy
`integration/optional/piano-aosp-notes.xml` to
`.repo/local_manifests/optional/piano-aosp-notes.xml` and copy it to the
top-level `.repo/local_manifests/` directory before `repo sync`. The piano
`seventeen` device tree includes `notes.mk` when this optional tree is synced;
`integration/device.mk.patch` records the one-line product include for other
checkouts. `notes.mk` adds the Soong namespace and app package. `Android.bp`
intentionally leaves `certificate` unset so Soong uses the product default
certificate and the ROM release-signing flow can apply its release key. Keep
that release certificate stable and install an APK signed with that same
certificate on the replacement phone. The app uses public Android SDK APIs
and does not request privileged permissions.

Android Auto Backup includes only `piano_notes.db`. Google-account restore
depends on Google Backup being enabled, Google's backup transport being
available, Android's backup schedule and restore choice, and installing this
app on the destination device. The app ID
`org.lineageos.pianonotes` and signing certificate must remain the same between
the source and destination builds; keep the release signing key stable across
ROM releases and distribute the matching signed APK for installation on a
replacement phone. Android's standard cloud backup has a 25 MB per-app quota; a
large ink database can exceed it, so use Export backup for a portable copy.
Device-transfer behavior also depends on the transfer providers on both
devices.

Import validates the versioned archive before replacing all local notes and
requires explicit confirmation. Export/import is independent of Google backup.
The ZIP is not password-protected; treat it as readable personal data and store
it somewhere private.

The Java sources compile against the local Android 34 SDK, and AAPT2 linked the
manifest and backup resources against that SDK. This is not a Soong build of
the Android 17 ROM. Tablet layout, stylus latency/pressure response, activity
lifecycle behavior, cloud/device-transfer restore, SAF providers, and product
integration still need review and device validation.

For a repeatable local SDK package, use `tools/build-local-apk.sh` with
`ANDROID_SDK_ROOT`, `NOTES_KEYSTORE`, `NOTES_KEY_ALIAS`, `NOTES_KEYSTORE_PASS`,
and `OUTPUT_APK` set. That script targets the locally available Android 34
build tools and uses only the keystore supplied by the caller; it does not
produce the Android 17 Soong or ROM-release-signed artifact.

The fallback archive streams newline-delimited note records inside a ZIP and
caps the uncompressed note data at 64 MiB. Import parses and validates records
inside one SQLite transaction; a malformed archive rolls back without deleting
the current library. Ink uses a fixed logical page size so its coordinates are
portable across screen sizes.
