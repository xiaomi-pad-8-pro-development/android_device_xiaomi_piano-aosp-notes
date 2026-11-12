# Xiaomi Notes interaction alignment

## Product direction

Keep native ALXP Notes. Use Notesnook as the navigation/editor layout reference,
Saber as the handwriting/page-tool reference, and Xiaomi Notes as the tablet,
floating toolbar, ruler and Xiaomi pen interaction reference. Do not replace
the native application with the Flutter or React Native apps.

## Target

Retain Material 3 dynamic colors and native AOSP controls while aligning the
tablet library, folders, full-page editing and handwriting tools with Xiaomi Notes.

## Implemented, awaiting build and runtime verification

- Tablet folder sidebar beside the note library, without an unused editor column.
- Dedicated full-width editor for text, handwriting and mind maps.
- Vector toolbar icons with accessible labels and tooltips.
- Sidebar icons for folders, notes, pinned, hidden and deleted notes.
- Visible ruler with tick marks; drag with a finger, rotate with two fingers.
- Stroke endpoints projected onto the ruler edge.
- Image/PDF handwriting exports omit ruler and selection overlays.
- Saved finger/pen, pen-only, and automatic stylus-detection modes.
- Standard Android stylus primary button selects temporary stroke erasing
  when held at the beginning of a gesture; Xiaomi event mapping is unverified.

## Evidence boundaries

REA inspected the local com.miui.notes APK, version 3.2.2.8, SHA-256
81510beeda9b87c3120c8835dbaa3faf9fed780aec90b9317904ba897f114e8f.
Package evidence: ev_1d41019411e70e9fb97e33be2a260263e5cc48d7cc04301d0a7c67b656ff2af2.
A case-sensitive class-name search for Ruler returned no matches
(ev_86120b8d1fe38ac8ca65ba3988409356dfcf417a24ed8023225e6e254c1cf3ed).
This does not establish absence of ruler behavior: obfuscation and native code
remain possible. These observations do not prove UI or runtime parity.

## Required acceptance checks

Build from Android 17 source with `m PianoAospNotes`.
On the tablet, inspect portrait and landscape in light and dark themes:

- Open each folder, switch filters, search, and reopen after rotation.
- Create and reopen text, ink and mind-map notes; confirm full-page layout.
- Navigate back and verify autosave and library scroll restoration.
- Check all pen icons, selected state, tooltips, settings and color controls.
- Move and rotate ruler, lift either finger, cancel, and toggle repeatedly.
- Draw along rotated guide using finger and stylus; check undo/redo and reopen.
- Check palm contact and simultaneous stylus/finger handling.
- Export image and PDF; confirm only note content is included.
- Compare Xiaomi folder navigation, editor controls and tool organization
  directly, recording remaining differences before declaring parity.

Resource XML parsing passed before the Fit page icon was added. Android SDK 34 aapt2 successfully compiled
this tree's resources to /tmp/alxp-notes-resource-check/resources.zip.
This validates resource compilation only, not Material dependency linking,
Java compilation, installation or runtime behavior. Diff whitespace checks pass.
The JDK compiler parser also parsed all 14 Java source files with source level
17 successfully; this is syntax validation and does not resolve Android or
Material symbols.
All 15 Java sources subsequently passed javac --release 17 type checking against
Android 36 and the builder's Material 1.14.0-alpha99/AndroidX AAR classes.
Temporary R classes supplied resource symbols for that check. This does not
validate final resource IDs, linking, dexing, packaging, signing or runtime.
No full APK build or runtime verification of these changes has been completed. Saber and Notesnook were
installed separately for user evaluation; they are not dependencies of this app.

## Xiaomi pen shortcut evidence

REA on MiuiHandWritingView.onKeyDown maps key 194 to stylus_pinch_status and
195 to stylus_double_click_status. Setting 4 chooses PARAMETER_DISK; 3 chooses
COLOR_DISK. The enum names were verified through gc.i static-initializer smali.
Evidence: ev_5a002ae8899774d12c6a31a837c62e467423eb464bb899b0a7d5e646f1720f87
and ev_2e1e330f4239d9763a63e4d878d42e162638f624e8041d98b5edf844e0568fd5.
The connected piano tablet currently reports both settings absent. Native ALXP
uses pen settings/color dialogs as fallbacks and honors explicit system values;
it does not write global settings. Input enumeration exposes stylus pressure,
tilt, distance and orientation axes; this is not physical pen-function proof.

REA touch dispatcher evidence ev_eef5cb7f5fafe8f3ae802715e9f525e9f367db00869e4dddfcc21daf9295b6f8
shows toolbox hit testing and focus-dependent event dispatch in
MiuiHandWritingView.dispatchTouchEvent. It does not establish palm rejection;
that behavior must be traced in the downstream canvas and tested physically.

## Remaining functional scope

The requested combined direction is not achieved by the current patch.
Multi-page handwriting now supports adding, switching, duplicating and confirmed
deletion, with page-aware undo/redo and rotation restoration. Two-finger pan/zoom
(1–6x), viewport constraints, and Fit page are implemented but not runtime-verified.
PDF export includes all pages; image export explicitly uses the current page.
PDF import/annotation remains unimplemented.
The Notesnook-inspired sidebar/search refinements are not a verified visual
match for its library/editor layout. Xiaomi popup actions currently open Material
dialogs rather than radial disks. Full visual and functional comparison remains
required, together with pressure/palm/button testing on the actual Xiaomi pen.

## Multi-page compatibility boundary

NoteStore treats ink_json as an opaque string, but InkView.serialize/load and
NotesTransfer.validateInk require a top-level stroke array. Multi-page storage
must update the editor, backup validator, export and recognition consumers
together while preserving legacy arrays. Merely wrapping the existing array
in a pages object would break backup import and the current reader. Existing
arrays must continue to open as a one-page document, and malformed new-format
input must fail before replacing the library.

## Current migration checks

The versioned codec passes 17 desktop compatibility checks using org.json
20240303. Run tools/check-ink-document.sh with that runtime JAR. These include
legacy preservation, multi-page round trips, malformed input, strict versions,
missing pages and page limits. Four additional temporary-harness checks invoked
the actual backup validator: legacy input, valid multi-page input, invalid
coordinates on a later page and aggregate stroke limits. These checks do not
exercise Android SQLite transactions, SAF, graphics, touch input or lifecycle.
Backup writers use format v4; readers retain v2/v3 support. Multi-page documents
cannot be read by older ALXP versions. Preserve a pre-upgrade backup before
using new-format documents; rollback to an old app requires restoring that
backup rather than discarding pages from a multi-page document.

Pending build review must cover all current changes, including InkDocument,
backup format v4 and page exports. The earlier /tmp review patch is regenerated
from the full current worktree. No commit or push has occurred.
