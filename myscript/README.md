# MyScript handwriting recognition

Notes converts handwriting to text with [MyScript iink](https://developer.myscript.com/),
the engine Xiaomi Notes uses. The SDK is free for personal use but needs a
developer certificate tied to your MyScript account, so neither the SDK nor
the certificate is in this repository.

To include it in a personal build, put these in one private folder:

- `MyCertificate.java` from the MyScript developer portal
- `iink-4.5.1.aar` from Maven Central (`com.myscript:iink:4.5.1`)
- `myscript-iink-recognition-text-en_US.zip` from
  `https://download.myscript.com/iink/recognitionAssets_iink_4.5/`

then, in the build tree, run:

    device/xiaomi/piano-aosp-notes/tools/enable-myscript.sh /path/to/private/folder

The script copies them into `myscript/private/` (ignored by git) and points
this module at them. Without it, Convert to text explains that recognition
is unavailable. Never commit `myscript/private/` or the certificate.
