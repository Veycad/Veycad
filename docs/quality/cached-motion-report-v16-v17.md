# Cached motion diagnostic, v16/v17

`tools/quality_cached_motion_report.py` reads one existing app-private analysis
cache through `adb exec-out run-as com.example.autoedit cat`. It does not copy
private files to shared storage, invoke semantic models, decode fresh frames,
replay pixels, run a director, or validate an exported product. The historical
`artifacts/quality/runs/inspect_v4_motion_cache.py` remains unchanged.

Usage:

```text
python tools/quality_cached_motion_report.py --adb ABSOLUTE_ADB_PATH --source-sha256 LOWERCASE_SHA256 --cache-version 17 --report NEW_REPORT_PATH.json
```

Only versions 16 and 17 are accepted. The two v16 face/gesture availability
Booleans and v17 optional camera record follow the exact Kotlin serialization
order. The camera method uses the ASCII subset of Java modified UTF-8; arbitrary
non-ASCII encodings are rejected. The complete attachment timeline, EOF and gzip
integrity are checked so a valid observation prefix cannot hide a truncated file.
The report is created exclusively; an existing report is never overwritten.

The SHA-256 is calculated from the exact compressed cache bytes obtained in that
single read. The supplied source hash identifies the cache filename, **not a fresh
verification of source bytes, APK identity, semantics or physical displacement**.
Those associations require separate runtime/source evidence.

The report distinguishes body measurement, independently retained camera,
camera-only measurement, and missing branches. Its `fear` section mirrors
`FearOpeningEvidence`: motion is validated body-relative movement OR validated
camera movement; static camera with unknown body remains inconclusive. Unknown
and static observations reset a run, gaps above 500 ms reset it, and a present
camera record must link its previous actual PTS to the preceding moving sample.
The unchanged threshold is compared using Kotlin Float (.18f) precision; vectors
represent interval displacement, not velocity. V16 body-only values do not contain
explicit previous/current PTS, so their historical linkage limitation is retained
and stated, not invented from observation timestamps.

The whole-cache motion run is a diagnostic, not a guarantee that a 3.6-second FEAR
opener will be selected or accepted. Physical camera/body accuracy, independent
ground truth, decoded video/audio checks and genuine human review remain separate.
Synthetic tests exercise both layouts, exact identity, missing/static branches,
linked PTS, cadence, union counts, threshold boundaries, malformed support,
truncation, old versions and exclusive report preservation. They are not real
footage acceptance.

## Cache18 explicit request profiles (2026-09-27)

The reader additionally supports cache18 only with an explicit `--profile`:

```text
python tools/quality_cached_motion_report.py --adb ABSOLUTE_ADB_PATH --source-sha256 LOWERCASE_SHA256 --cache-version 18 --profile editorial-semantics-v1 --report NEW_REPORT_PATH.json
python tools/quality_cached_motion_report.py --adb ABSOLUTE_ADB_PATH --source-sha256 LOWERCASE_SHA256 --cache-version 18 --profile editorial-correspondence-v1 --report NEW_REPORT_PATH.json
```

Key `v18-{profile}-250000-{source_sha256}.bin.gz` and serialized ASCII Java UTF
profile must agree. Header18 stores magic/version, UTF profile, completed
correspondence-assessment count, then the earlier duration/semantic/mask/model/
observation counters. Observation and attachment layouts remain v17. Unknown
profile, truncated UTF/header, profile/key disagreement and invalid counts reject
the file; no profile is guessed from nullable observations. Historical16/17 keys
and report fields remain unchanged and explicitly reject any supplied profile.

`editorial-semantics-v1` requires zero correspondence assessments and null typed
body AND camera records throughout. Its state is `NOT_REQUESTED`, not attempted
but unknown motion. Its FEAR physical verdict/run/unknown/conclusive fields are
null, `assessed=false`, and `not_requested_samples` records the observation count.
It must not fabricate `insufficient_motion`, motion absence, a negative FEAR test
or conclusive measurement from intentional non-execution. Stored measured counts
remain0, while body unknown count is null to distinguish absence of assessment.

`editorial-correspondence-v1` requires completed assessments exactly equal to
observation count, including first-frame or failed/unknown nullable results. Its
state is `ASSESSED`; existing whole-body/camera OR and attempted-but-unknown counts
are then calculated. Count is NOT the number of nonnull/successful measurements.
The reader neither changes Android cache profiles nor launches any models/builds.
New synthetic tests cover both explicit profiles and all-unknown versus
not-requested meaning; they do not establish runtime speed or product acceptance.
