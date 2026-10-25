# Google dependency notices

`app/src/main/assets/licenses/google/index.json` maps 19 resolved Google,
Firebase and ML Kit artifacts to their supplied `third_party_licenses.json`
and `third_party_licenses.txt`. The original files are preserved byte for byte;
identical contents are stored once under their SHA-256 and original extension.
These files add no SDK, permission or network request.

The upstream JSON files index byte ranges in their paired UTF-8 text. All
1,083 indexed ranges were checked for positive lengths, valid boundaries,
non-overlap and UTF-8 decoding. This count includes repeated notices across
artifacts, not 1,083 distinct dependencies. Four artifacts supply an empty JSON
object and empty text; those pairs are preserved, not interpreted as a license
waiver. There are 24 distinct upstream files plus the mapping index, totalling
9,715,461 bytes before APK compression.

The inventory records artifact coordinates, filenames, notice sizes and hashes.
The files came from the corresponding resolved AARs in Google's Maven repository.
On dependency upgrades, extract and compare the new AAR's notice pair and update
the mapping. Verify that APK assets match the source files before release.

This package preserves upstream notices, including references to transitive
components that may not all be used by Veycad. It does not replace SDK terms,
the application's privacy policy, MediaPipe's separate NOTICE, or the audit of
native libraries, models and music. User-facing access to the full collection
and verification of the final signed APK remain open release tasks.
