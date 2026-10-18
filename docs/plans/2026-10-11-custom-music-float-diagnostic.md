# Diagnostic-only post-AAC float witness

Authority: coordinator's delegated user decision on 2026-10-11 approves a
bounded remote API26/API36 x86_64 experiment after independent proposal review.
This does not authorize shipping FFmpeg, selecting a production dependency,
weakening quality floors or changing the immutable CI20 native failure.

## Constraints

- Work only in codex/custom-music; preserve original dirty checkout.
- No local compile, Gradle, emulator, native decode or render processes.
- No video render. Decode saved CI20 MP4 and a pinned compressed AAC control.
- Production code, pre-AAC 1dB gain and all QA floors remain unchanged.
- The software decoder is present only in the optional Android test APK.
- One x86_64 Android binary with min API26 is tested on API26 and API36.
- ARM64 evidence and phone/artistic acceptance remain open.
- Each coherent checkpoint has scoped commit, independent review and exact-head CI.

## Task 1: materials and independently fixed controls

Pin a reviewed FFmpeg source revision and Android NDK/build configuration.
Remote-only build produces minimal AAC float decoder libraries and a diagnostic
JNI binary. Preserve source/config/binaries/hashes, licenses and build logs.
Separately generate a short AAC-LC overshoot control and independently decode
the compressed bytes to retained float PCM. Its >1 sample must be in the
presentation area, not merely in source PCM or codec priming.

Produce a manifest binding input MP4/control SHA, expected AU sequence
count/rolling hash/PTS, actual raw/reference float samples, codec delay/padding
and presentation interpretation. No candidate results are disclosed before
these expected identities are pinned and independently reviewed.

## Task 2: optional software diagnostic

JNI explicitly selects decoder aac, uses padded CSD/AUs and bounded planes,
checks every actual AVFrame format/rate/layout, and copies raw FLTP bits without
integer intermediate, gain, resampling or clamp. Require extractor EOF and
libavcodec drain to AVERROR_EOF; one AU need not produce exactly one frame.

AV_CODEC_FLAG2_SKIP_MANUAL and saved AV_FRAME_DATA_SKIP_SAMPLES keep one owner
of presentation crop. Preserve raw and presentation ledgers, every crop reason,
and ambiguity; never trim to 16 seconds merely to pass duration.

Three cases: saved CI20 MP4, compressed AAC overshoot, integer-to-float negative.
Separate decode_complete/unclamped_float_witness_complete/audio_quality_pass.
Overshoot requires first two true and quality false. Retain real f32le bytes,
input/output ledgers, binary/APKs and hashes, actual formats, API/ABI/build,
partial failures, original snapshot and unchanged DecodedAudioQuality metrics.

## Task 3: two-stage remote execution and independent review

First publish reviewed materials/build-only checkpoint and run remote preparation.
Independently verify artifact and pin its immutable run/artifact/SHA/binary/input
identities before the Android candidate experiment. Then publish diagnostic
checkpoint and execute exactly expected optional tests on remote API26/API36.
Review XML, all manifest payloads, sample bytes/metrics and provenance independently.
Run applicable baseline for the exact checkpoint; no prior success substitutes.

## Task 4: report bounded results

Update draft PR18 with exact-head build/material and Android CI results,
independent reviews and unchanged native false/weak transitions/colour-jump.
Shared selector PR37 remains a separate additive owner handoff; no second
selector or wholesale graph/model replacement belongs to this experiment.

## Acceptance

The experiment succeeds only when source/config/binaries/input identities and
complete packet/frame/raw/presentation ledgers are verified, actual float is
retained, and the overshoot control remains >1 and fails the existing evaluator.
Missing coverage, ambiguous crop, fallback integer or incomplete drains remain
explicit gaps. A diagnostic success does not establish a release/device gate.

## Reviewed preparation checkpoint

Remote run38154571916 at ef0788b4ea5cb32b5e36d135f3d85bb5ca0d2ab1 succeeded.
Independent review cleared the actual artifact11707458538 before Android
execution: 63 files, 62 bound payloads, all hashes verified; 124480120 total
uncompressed bytes, largest file89825280 within the bounded extraction limits.
Immutable run/ZIP/material/JNI/source identities are in software_aac_pins.json.

Actual ELF is API26/x86_64, NDK r27c/12479018, with 16KiB LOAD alignment and
system libm/libz/libdl/libc dependencies. Source/tag/config/licenses and JNI
source match the reviewed build. The host frame ledger exposes stream rate
only; Android must still check the rate on each actual frame.

The native MP4 has 251 AUs and 257024 raw/presentation mono16000Hz frames,
zero crop, and unchanged original native false. The compressed overshoot
control has 95 AUs, 97280 raw frames and 97024 presentation frames after the
MP4 endpoint's 256-frame tail crop, mono48000Hz. Independent float-byte checks
confirm 377 presentation samples above full scale, peak1.8011534214. The
2.021333s presentation endpoint comes from MP4 metadata, not a forced 2s crop.
No Android candidate results existed when these expected identities were fixed.

Initial failed preparations remain recorded: run38153931950 lacked sdkmanager
on PATH; run38154206014 used the CLI raw format name instead of configure
pcm_f32le component names. Separate reviewed fixes preserved those artifacts.
Baseline38154208057 at bb8d844d passed643 JVM/255 Python with no failures or
skips, but is not the later pinned-execution checkpoint's final baseline.
