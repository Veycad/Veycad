# Transient source-audio preparation

`MediaCodecAudioDecoder.sourceProvider(files, metrics, checkCancelled)` snapshots the stable ID
to absolute file path lookup. The caller keeps those files immutable while composing. The first
audio track is selected, even when video precedes it. Only a valid media input without an audio
track returns null. Missing/unknown/corrupt inputs, incompatible format changes and cancellation
throw. Actual decoder PCM rate, channel count and encoding determine the fixed stream format;
PCM8/16/float are converted to signed16, and more than two channels are averaged to mono.

Raw decoder buffer PTS are retained, including late audio and measured gaps. Negative codec
priming is cropped on complete sample boundaries. Interior opens use 500 ms MP3 preroll and
clear original encoder delay only when the seek passed the first packet. They retain the
interpolation predecessor; they never subtract video origin or invent audio EOF from metadata.
Each output buffer must fit one second. EOF is sticky until close/reopen. The decoder owns its
extractor and codec; the compositor owns each returned stream. Calls are sequential, not shared
between threads. Source resampling and canonical time maps remain the reviewed T2A implementation.

For app music, `loopingMusicProvider(file, startUs, metrics, checkCancelled)` loops the selected
tail at actual decoded EOF, retaining measured gaps and assigning output sample-clock PTS from
zero. It holds at most one pending source chunk and uses at most one music decoder. Empty selected
tails fail. This wrapper is for the new mix modes; existing MUSIC export and its gain range remain
on the legacy entry points. The fc21f629 music offset/encoder-delay/window fixes are preserved.

`AacEncoderMuxer.prepareSourceAudio(composer, settings, directory, music, checkCancelled)` makes
one composition and returns an owned `SourceAacPreparation`. Its `aacFile` is a complete AAC/M4A
track. `speechPcmFile` is raw little-endian signed16 mono at 16 kHz, before source gain, headroom
or app music. `sampleFrames` counts actual 48 kHz frames; no video is needed to prepare speech.
The AAC sink consumes borrowed PCM scratch synchronously and drains each packet directly to
M4A. It keeps no whole-timeline array or packet list. The preparation caller must close the
artifact after all borrowers finish; close removes both files. Failed/cancelled preparation
removes only its unique owned temporary files. STT does not take file ownership.

After the existing recognizer consumes the borrowed speech PCM and the shared renderer produces
video-only output, call `muxPreparedAudio(prepared, videoFile, outputFile, videoEndPtsUs,
checkCancelled)`. The exclusive raw video endpoint must come from independent renderer/frame
evidence. It must not be computed as first PTS plus declared duration. The mux copies actual
compressed video samples, PTS, sync/partial flags and rotation, interleaves prepared audio, and
writes the explicit video endpoint and exact audio presentation edit. It rejects encrypted tracks and samples above
16 MiB. On API 26/27 the fixed 16 MiB buffer avoids using the API 28 sample-size getter. Output is
staged beside its destination and atomically replaced after successful muxer close and the final
cancellation check. Unsupported atomic replacement fails while preserving the previous output.
Video and prepared audio files are borrowed and never deleted by mux.

Metrics describe Java-visible PCM chunks, open decoder counts and maximum observed decoder/AAC
buffer size, not codec-internal memory or total RSS. The compositor reports its own retained PCM;
conversion may transiently hold two signed16 arrays of at most one second, the music loop one
pending chunk plus its output conversion, and mux one 16 MiB compressed-sample buffer. Source
and music together may open two decoders. Runtime measurements are required to accept these
bounds on real codecs.

Source encoding owns one additional 4096-byte stereo PCM block. It coalesces borrowed writes
into 1024-frame blocks, copies the last borrowed input, and zero-pads only the unused samples of
the final partial block. `acceptedFrames` remains the authored duration; `queuedFrames` includes
this final padding. Every data work contains exactly 1024 frames without EOS. A separate empty
EOS work uses the padded endpoint. The encoder's packet timestamps and bytes are never restamped.
The strict output timestamp audit remains at two microseconds, including diagnostic observations.

The previous nonempty-EOS policy was disproved on API36. Its
[CCodecBufferChannel](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/media/codec2/sfplugin/CCodecBufferChannel.cpp)
splits a nonempty EOS buffer into data and an empty work at the same timestamp. For the partial
48,000-frame probe, C2 clamped the hidden work to 999,999 us, then emitted packet47 at 1,021,332 us
instead of 1,002,666 us. The observed full-block probe preserved cadence. Explicitly padding the
last input supplies that same full-block contract without inventing packet metadata; authored
presentation length still comes from the existing exact edit-list trim. Fresh exact-head native
checks must verify both partial and full endpoints before this policy is accepted.

Source AAC timing is measured for each selected encoder component with the exact 48 kHz,
stereo, 192 kbps configuration. Two sequential one-second synthetic probes use the existing
encoder and decoder owners: the first measures stable early/middle/late marker delay; the second
requires negative extractor packet PTS, matching trim metadata, unchanged marker positions and
exactly 48,000 decoded frames. A codec/container mismatch fails before real composition. No
universal AAC delay is assumed. The new path currently requires API 35+; API 26–34 support is
an unresolved release gate, while legacy MUSIC remains on its existing API.

All AAC packets are drained and preserved. Measured priming is written as negative PTS, causing
the supported MediaMuxer to write an edit list. `AacMp4Trim` corrects only fixed-size movie-unit
fields in owned, non-fragmented version-0 containers: promotes the movie timescale to its exact
common multiple with 48 kHz, rescales mvhd/tkhd/elst fields, and sets audio edit duration from actual
input samples. Coded mdhd/stts, media bytes and chunk offsets remain intact so the extractor can
derive both delay and trailing padding. Unsupported structures/overflow fail before field writes.
The generated top-level `mdat` supports MediaMuxer's 16-byte extended-size header; its unsigned
size must fit the file address range and remaining bytes. Other extended atom types are rejected.

For a full decode from the beginning of a proven gapless AAC-LC track, the decoder additionally
checks the AAC configuration, contiguous 1024-frame packet cadence, edit duration, delay and
padding before using an owned PCM sample clock. Android's SkipCutBuffer can retain trailing
padding samples across buffers while preserving the next packet timestamp: with padding128,
896 returned frames precede a buffer stamped at frame1024. The correction derives timestamps
from actual returned PCM counts and the validated origin; it neither changes AAC packets nor
adds a calibration offset. It rejects unexpected decoder timestamps and requires the exact
expected final PCM count. Generic source gaps and interior seeks retain the original decoder
path rather than being flattened into this clock. Fresh native and independent host waveform
checks remain required; a clock unit test alone does not establish container acceptance.

Final mux preserves the extractor's already-negative audio PTS; it must not subtract delay again.
`KEY_DURATION` can describe coded media duration including priming/padding; presentation duration
comes from the edit, and acceptance separately checks actual decoded sample count and A/V end.

Calibration owns at most one encoder or decoder at a time, a 4096-byte probe scratch buffer and
the encoder's separate 4096-byte pending block;
it closes before source/music decoding starts. Probe stream metrics and elapsed microseconds are
logged separately under `SourceAacTiming`, not included in caller provider metrics or total RSS.
Temporary probes are removed on success/failure/cancellation. This costs two short encode/decode
passes per preparation; there is no persistent or cross-project calibration cache.
Probe ledgers record input start/count/PTS/EOS, accepted versus submitted frames, raw output
PTS/size/flags before the strict packet guard, container packets/format and decoded chunks/counts.
They stream to `SourceAacTiming` logcat with a 256-row cap per stream and explicit truncation/count
summary; debug preparations also log input/output rows. No packet list is retained.

The current diagnostic checkpoint permits only the raw synthetic calibration probe to defer its
first timestamp violation while preserving every raw packet's bytes, order and PTS. It drains
actual output EOS, closes the same muxer, and inspects/decodes the resulting raw container. On a
mismatch it also runs a sequential 51,200-frame full-block probe. Both use the existing owners.
The diagnostics record cumulative decoded sample positions alongside unchanged chunk-PTS
positions for the first/middle/tail markers, coded/container packet counts, actual decoded sample
count and discontinuities. They reject lost container packets, a decoded count different from
complete coded access units, missing markers and unreleased decoder ownership. Even plausible
waveform evidence cannot pass preparation: the first timestamp failure is rethrown before the
trimmed probe or real composition. The default real/trimmed encoder guard remains at two
microseconds, and the five-file host acceptance gate remains unchanged.

Diagnostic work is bounded to 48,000 or 51,200 input frames, 51 input works (including empty EOS),
256 output events,
256 packets/chunks, 262,144 decoded frames, 64 KiB per encoded packet and 2 MiB of encoded data
or retained container. Encoder and diagnostic decode phases each have a 60-second limit in
addition to existing stall/cancellation checks. The decode deadline starts before opening the
source provider and follows its existing cancellation callback through priming and internal read
iterations, without renewal on progress. The caller also checks it after each read, including EOF,
and around sample observations. Each budget check calls the original cancellation callback first.
The two probes are sequential; no whole PCM or AAC array is retained. In debug instrumentation's
existing `source-audio-native-evidence` case directory only, `diagnostic-*` files retain the
synthetic raw containers, streamed input/output/
container/chunk ledgers, exact encoder/container CSD-0 hex (bounded to 256 bytes), and
`.diagnostic-sha256.txt` hashes. These names are separate from accepted
artifacts and cannot satisfy the five acceptance sidecars. Normal owned scratch files are still
deleted on failure/cancellation. Failed/incomplete observations are not complete EOS or decode
evidence; exact-head remote execution is required. No packet normalization is authorized by this
checkpoint. Preparation-dependent native tests remain a required exact-head gate; the earlier
diagnostic checkpoint failures are not acceptance evidence for the corrected input/PCM clocks.

The UI runner retains generated WAV/M4A/MP4 evidence byte-for-byte using a binary stream and
archives its SHA256. CI's separate FFmpeg native float AAC oracle checks the exact device-recorded
AAC hash, actual planar-float format, unclamped peaks/plateaus, sample count and boundary markers
in both prepared M4A and final MP4 at 30/60 fps. Missing/malformed evidence fails CI. PCM16 output
from Android is recorded as PCM16; host float proof does not establish Android float support or
human listening acceptance.
Each of the five consumed files has a device-recorded path/SHA256 sidecar written after its native
assertions. The host verifies the complete expected set and whole-file hashes before any decode,
including marker containers' non-audio bytes, and rejects missing, extra or ambiguous entries.
Instrumentation clears the evidence directory once per run and preserves only that named
directory across later UI-case resets. Other app state still resets normally. Transfer goes to
an owned `.partial`; nonempty safe tar validation must succeed before publication/hash. Remote
error text, empty/truncated or ambiguous archives fail with retained diagnostics while native XML
is still collected. These transfer checks do not replace the five-artifact acceptance gate.

The container policy follows the inspected AOSP
[MPEG4Writer negative-start edit handling](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/libstagefright/MPEG4Writer.cpp)
and [MPEG4Extractor edit/trim handling](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/media/module/extractors/mp4/MPEG4Extractor.cpp).
The codec evidence comes from [C2SoftAacEnc](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/media/codec2/components/aac/C2SoftAacEnc.cpp),
[C2SoftAacDec](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/codec2/components/aac/C2SoftAacDec.cpp)
and the [FFmpeg floating-point AAC decoder](https://ffmpeg.org/doxygen/8.0/aacdec__float_8c_source.html).
Source inspection establishes the policy to test; it does not substitute for exact-head runtime proof.

The shared compiler/snapshot adapter, text rendering and existing STT adapter are separate gates.
Muted source still produces pre-gain speech PCM intentionally; T4/T8 must enforce audible-source
caption eligibility. This API does not transcribe, persist another project, or establish UI
readiness. Native `SourceAudioDeviceTest` fixtures include 30/60 fps marker/duration checks,
independent origins and gaps, first-track selection, EOF/repetition/HOLD, cancellation/re-export,
legacy MP3 windows and float AAC peak evidence. Until those run on the exact commit, codec timing,
encoded peaks and resource behavior remain unverified. Listening to retained WAV/AAC output and
final shared compiler/export parity are also required before accepting original T2.
