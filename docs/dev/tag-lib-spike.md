# TagLib SPIKE

## Artifact

- Coordinate: `io.github.kyant0:taglib:1.0.6`
- Latest version checked on Maven Central: `1.0.6`
- Publish date: `2026-04-15` (Maven Central directory listing timestamp: `2026-04-15 10:00`)
- Sources: [Maven Central](https://repo1.maven.org/maven2/io/github/kyant0/taglib/1.0.6/), [POM](https://repo1.maven.org/maven2/io/github/kyant0/taglib/1.0.6/taglib-1.0.6.pom), [upstream repository](https://github.com/Kyant0/taglib)

## License and native library

The published POM says:

> `<name>The Apache License, Version 2.0</name>`

> `<url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>`

The upstream `LICENSE` starts with `Apache License Version 2.0, January 2004` and ends with `Licensed under the Apache License, Version 2.0`. This is an allowed Apache license.

The upstream build configuration says it bundles `TagLib 2.2.1`. TagLib is licensed under the Mozilla Public License 2.0 (MPL-2.0), which is an allowed license for this spike.

## AAR inspection

Downloaded: `taglib-1.0.6.aar` from Maven Central.

The extracted AAR manifest contains:

```xml
<uses-sdk android:minSdkVersion="23" />
```

This is compatible with Sur's minSdk 26.

Native entries and uncompressed entry sizes:

| ABI | Entry | Size (bytes) |
| --- | --- | ---: |
| arm64-v8a | `jni/arm64-v8a/libtaglib.so` | 1,254,376 |
| armeabi-v7a | `jni/armeabi-v7a/libtaglib.so` | 807,168 |
| x86 | `jni/x86/libtaglib.so` | 1,210,912 |
| x86_64 | `jni/x86_64/libtaglib.so` | 1,266,912 |

## API findings

The API takes a file descriptor, not a file path. The version-pinned upstream tests open a `ParcelFileDescriptor`, then call `fd.dup().detachFd()` and pass the resulting integer to `TagLib.getAudioProperties`, `TagLib.getMetadata`, `TagLib.getPictures`, `TagLib.savePropertyMap`, and `TagLib.savePictures`.

Cover art is written by constructing `Picture(data, description, pictureType, mimeType)` and passing one or more `Picture` values to `TagLib.savePictures(fd, pictures)`. The tests use JPEG bytes with `mimeType = "image/jpeg"`.

A custom field is written by adding a value to the metadata `propertyMap`, for example `propertyMap["SUR_ORIGINAL"] = arrayOf("test")`, and passing the map to `TagLib.savePropertyMap(fd, propertyMap)`. Removing that map key is the restore operation.

## Format claims found

The README claims only: `An Android library which can read and write tags of audio files.` It does not enumerate formats. The version-pinned README-linked `src/androidTest/kotlin/Tests.kt` exercises `read_and_write_m4a`, `read_and_write_pictures_flac`, and MP3 files in `ensure_utf8` and `bad_encoding`. Thus the formats explicitly demonstrated by the upstream tests are M4A, FLAC, and MP3; no broader format claim is made here.