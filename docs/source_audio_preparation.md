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
writes explicit final track duration markers. It rejects encrypted tracks and samples above
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

The shared compiler/snapshot adapter, text rendering and existing STT adapter are separate gates.
Muted source still produces pre-gain speech PCM intentionally; T4/T8 must enforce audible-source
caption eligibility. This API does not transcribe, persist another project, or establish UI
readiness. Native `SourceAudioDeviceTest` fixtures include 30/60 fps marker/duration checks,
independent origins and gaps, first-track selection, EOF/repetition/HOLD, cancellation/re-export,
legacy MP3 windows and float AAC peak evidence. Until those run on the exact commit, codec timing,
encoded peaks and resource behavior remain unverified. Listening to retained WAV/AAC output and
final shared compiler/export parity are also required before accepting original T2.
