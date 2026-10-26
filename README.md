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
- Stroke erasing with touch mode or the stylus eraser end, plus Undo and Clear
- Material 3 Expressive dynamic day/night styling, with adaptive note cards for phone and tablet widths
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
and does not request privileged permissions. The UI links Soong modules
`com.google.android.material_material` and `androidx.core_core` for Material 3
Expressive components, dynamic color, and edge-to-edge insets.

Android Auto Backup includes only `piano_notes.db`. Google-account restore
depends on Google Backup being enabled, Google's backup transport being
available, Android's backup schedule and restore choice, and using the same
Google account and backup dataset on the destination device. The app ID
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

The standalone Android SDK builder does not include the Material Components
classes and resources used by the current UI. Build this revision from an
Android 17 source checkout with `m PianoAospNotes`; the older locally signed
test APK predates the Material 3 Expressive UI and is not a build of this
revision. Tablet layout, stylus latency/pressure response, activity lifecycle,
cloud/device-transfer restore, SAF providers, and product integration still
need review and device validation.

`tools/build-local-apk.sh` directs ROM-tree builds to Soong and explains why a
standalone SDK build cannot resolve the Material 3 dependency.

The fallback archive streams newline-delimited note records inside a ZIP and
caps the uncompressed note data at 64 MiB. Import parses and validates records
inside one SQLite transaction; a malformed archive rolls back without deleting
the current library. Ink uses a fixed logical page size so its coordinates are
portable across screen sizes.
