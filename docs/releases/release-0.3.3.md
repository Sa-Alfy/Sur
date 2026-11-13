# Release v0.3.3

## What's new
- **Groundwork (data layer, cache, stable keys) with no visible change yet**:
  - **Data layer**: Room database schema and repository for offline metadata overlays.
  - **Cache**: disk caching and image scaling for cover artwork.
  - **Stable keys**: cross-device song keys preserving overlays across library rescans.
- **CI automation**: automated continuous integration pipeline testing builds on push.

## Updating
- **From v0.3.2, v0.3.1, v0.2.0 or v0.1.0**: install over it as usual. Nothing is lost.
- **From v0.3.0**: v0.3.0 was signed with a development key, so Android won't update it. Uninstall it first, then install v0.3.3. Uninstalling deletes your playlists; your music files are not touched.

Signing certificate SHA-256: `DA:D2:6A:E7:86:B2:52:6A:36:B6:81:F3:3F:F3:BE:1C:6C:76:1A:80:6C:EF:6C:0D:A3:6E:68:65:D7:75:6F:42`
