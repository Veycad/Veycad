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
