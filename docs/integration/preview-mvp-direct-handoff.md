# Restricted direct MANUAL preview handoff

Public API in `com.veycad.app`:

```kotlin
class DraftPreviewController(onState: (State) -> Unit) : AutoCloseable {
    fun open(request: MediaCodecSpeedRampRenderer.Request)
    fun attach(surface: android.view.Surface, size: OutputSize)
    fun detach()
    fun play()
    fun pause()
    fun beginScrub()
    fun seekTo(outputTimeUs: Long)
    fun endScrub()
    fun stopAndRelease(onStopped: () -> Unit)
    override fun close()
}
```

Construct and call on main. Commands return without waiting for decode, EGL, audio preparation or disposal. `close()` is asynchronous and terminal. `stopAndRelease` stops the current session and permits another `open` after its success callback; a failed cleanup is retried by calling `stopAndRelease` again. Do not issue other commands while stopping. All state and success callbacks run on main.

`State` is immutable: `generation: PreviewGeneration?`, `requestedOutputTimeUs: Long`, `shownOutputTimeUs: Long?`, `sourceIndex: Int?`, `decodedSourceTimeUs: Long?`, `playing: Boolean`, `exactFrameReady: Boolean`, `buffering: Boolean`, `warning: String?`, `error: String?`. Initial values are null/zero/false. Requested time is the raw user request; shown time is from the SAME prepared canonical frame. Actual source texture PTS is separate. Exact means the canonical video composition was submitted with the decoder's accepted image and matching SurfaceTexture PTS, not measured display scanout or measured audio alignment. Buffering describes pending video work; a prepared image can be exact while audio preparation is still pending.

## Caller ownership and input

Create the request on the existing preparation/export seam documented in [renderer handoff](preview-mvp-renderer-handoff.md). The controller requires its sealed `preparedExport` binding, validates it on GL, and uses `prepared.framePlan` directly. Legacy requests fail through error state; no inference/recompile/export is attempted. Source indices remain full saved selection indices, including duplicate files and unused slots. The outputFile is never created, deleted or rendered by preview.

The caller serializes preparation, edits and consumers against the SAME installed immutable revision. Retain original source/music bytes until every consumer stops; stop preview before export, source replacement, deletion or reimport. This controller does not lease or delete source files.

Every supplied Surface is borrowed. **Retain every replaced/detached Surface and its SurfaceTexture until `stopAndRelease` succeeds**, even after `detach` or a newer `attach`. A TextureView owner must return `false` from `onSurfaceTextureDestroyed` while stopping; release that retained Surface and SurfaceTexture only in the callback. Never join/wait on the UI thread. Attach stays paused; detach invalidates generation and stops audio. Reopen/format changes retain the last shown output clock. The 360-short-edge composition uses the binding's aspect (360x640, 640x360, 360x360 or 360x450); the existing display target fits it to the actual physical Surface. `size` records attachment dimensions for compatibility/rebuild; it does not override project aspect or increase composition resolution.

## Supported admission

Only existing analysis-free prepared MANUAL inputs at saved 30/60 FPS and 720/1080 export settings are admitted. Basic trim/reorder/time maps, local clip transforms and grade values use the exact existing frame values. Supported `MontageGraph.Transition`: `OPEN`, `HARD_CUT`, `FINAL_HOLD`. Supported overlay kind: non-temporal `FLASH`, with existing blend modes/colors/opacity. The public `manual-basic-test` graph shape (OPEN, HARD_CUT, flash-B) is covered by the authored native fixture.

Reject before target/decoder creation: `WHIP`, `OCCLUSION`, `FOREGROUND_REENTRY`, `BLACKOUT`; other overlay kinds (`GLOW`, `VIGNETTE`, `DOUBLE_EXPOSURE`, `MIRROR_SLICE`, `BLACK_FADE`, `SUBJECT_STAGE`); every active `GpuEffectGraph.Kind` (`GLOW`, `GLITCH`, `LENS_BLUR`, `DEFOCUS_BLUR`); mixed/secondary/temporal roles, flow, analysis attachments, heartbeat echo and final-fade samples. Authored title/Heartbeat/Fear/Duality/Reference automatic profiles reject explicitly. Existing preparation/adapter rejection of text/captions, analysis-backed winners, SMART_PERSON, BLURRED_FIT, 4K and unsupported music edits remains intact. No shader, compiler, store, factory, pool, editor or Activity is added.

## Scheduling, audio and memory

One plain scheduled executor owns GL (no Looper), one existing `PreviewSeekQueue` keeps only the latest pending seek, and existing `SourceDecoderSlots` owns one active incoming `VideoDecoderCursor`. Default SurfaceTexture signals can run on main while GL waits; await/codec loops poll cancellation. Forward decode retains actual PTS; reverse requests seek previous sync and decode forward. A cancelled decode retires both codec and compositor inputs; its outstanding image cannot be accepted as a new generation. Shared compositor draw evidence verifies actual SurfaceTexture PTS equals the accepted cursor PTS. Stale state/audio callbacks recheck generation before acting. A native swap/start already entered before invalidation cannot be recalled; obsolete state callbacks are suppressed and a raced audio start is paused immediately after return. No promise of retracting GPU work already submitted to the platform.

Nearest paused frame is selected by binary search in the existing saved plan, ties to the earlier frame; negative requests reject and requests past end select the last frame. A saved 60 FPS plan remains 60 FPS for paused seeks. Playback samples at roughly 30 Hz and may skip frames while keeping canonical timestamps. No p95/performance claim.

`PreviewAudioClock` is internal. It creates/owns one MediaPlayer on its own HandlerThread for the original local audio file; a user seek recreates this single player so old native seek callbacks cannot be confused with a newer seek. Async prepare plus seek-complete and exact-frame readiness are both required before start. Scrubbing remembers the prior play state; paused scrubbing stays paused. Music must be finite, positive-duration, audio-only and playable by the platform; invalid formats/prepare/seek timeout report errors. No source-video audio, remix, new encoding, gains, fades or offsets. The adapter's unchanged offset-zero/unity-gain/repeat contract applies. Null audio explicitly uses a silent monotonic clock.

Seek uses output time modulo track duration; timestamp/currentPosition is unwrapped to the global output cycle nearest the monotonic anchor. Android documents [getTimestamp](https://developer.android.com/reference/android/media/MediaPlayer#getTimestamp()) as nullable and its media anchor as current position; this path falls back to [currentPosition](https://developer.android.com/reference/android/media/MediaPlayer#getCurrentPosition()), then monotonic prediction. The audio handler independently cuts at the prepared global output endpoint even if GL is busy. Timing is MediaPlayer/Handler millisecond granularity, not sample-accurate export muxing. Audible output, long playback stalls/loop drift and device A/V timing have not been measured.

Exactly one existing `PreviewMemoryBudget(32 MiB)` is created lazily on GL and supplied to the reviewed compositor allocation path. No foreign clear or forced GC. Prepared CPU headers/canonical plan and EglRenderTarget-owned backing are outside that accounting: this is **not** a whole-app 32 MiB or physical memory bound. Compatible same-aspect MANUAL changes reuse the compositor/table; other changes close/rebuild at the retained output clock. Advanced resize/cache preservation is deferred.

Stop order is decoder slots, compositor allocation owner, target, followed by audio-owner release acknowledgement. Visible cleanup failures retain owner references and `PreviewAllocationFailure.retryClose`; no success callback fires until explicit retry completes. `onStopped` means no more decode/draw scheduling is authorized, existing owner close APIs returned, and actual MediaPlayer release returned. Existing VideoDecoderCursor suppresses some native release errors and EglRenderTarget does not expose all EGL destroy return values; this callback cannot independently prove those hidden driver disposals.

## Evidence

Authored initial JVM policy/grid/music and real native Surface tests before production files. The native fixture uses real synthetic video, ImageReader, shared GLES, full source order with an unused duplicate, reverse/source-change known pixels and PTS, a worker-boundary cancellation, and retained bytes/no preview file checks. During static implementation review it gained packaged original music prepare/seek/play/pause/detach/release coverage and a controlled cancellation gate. Pure policy tests exercise the SAME `PreviewPlaybackPolicy` used by controller frame/audio readiness and MediaPlayer start authorization, but do not simulate native callback ordering. The native fixture's state acknowledges actual MediaPlayer calls; it is not an audible or measured drift oracle.

All compilation, Gradle, NDK, ADB, emulators, renders and tests are **UNRUN locally** under the closed resource queue. Test-first authoring order is not behavioral RED/GREEN. Intended root verification is exact-head remote baseline and Android UI discovery of `PreviewPlaybackPolicyTest` / `DraftPreviewControllerDeviceTest`; no runtime, physical-device or artistic PASS is claimed.
