# Preview benchmark raw contract v1

`tools/preview_benchmark_report.py` is an offline calculator. Its output describes
only supplied raw evidence: it never grants product/visual approval, proves a
physical clock, enables a feature gate or verifies runtime allocator wiring.
Task 13 collector, runner, physical devices and human review remain pending.

```powershell
python tools/preview_benchmark_report.py input.json --output report.md
python -m unittest tools.test_preview_benchmark_report
```

Exit codes: `0` all recorded group metrics PASS; `1` FAIL or INCOMPLETE (Markdown
still written); `2` invalid input, unsupported schema or I/O error. Library API:
`evaluate(document) -> dict` raises `ValueError` for malformed input;
`render(evaluation) -> str`. Empty evidence is INCOMPLETE. FAIL has precedence
over INCOMPLETE; counts and individual failure reasons remain visible either way.

## Schema

Root: `{"schema_version": 1, "groups": [...]}`. Each group is independent;
never pool devices, source sets/profiles, revisions or scenarios to make quotas
or p95 pass. Collector/runner must split these identities before serialization.
All fields below are required unless explicitly noted. Unknown fields are
rejected. Numeric fields are nonnegative JSON integers (not booleans, fractional
values, NaN or Infinity). Strings are nonempty. `null` means unknown, never zero.
All IDs must be stable opaque IDs: duplicate group IDs, event tokens, pass IDs,
scrub IDs, frame indices within a pass, and memory timestamps are rejected.
Duplicate JSON object keys are rejected by the CLI too.

| Group field | Shape / meaning |
| --- | --- |
| `id` | Opaque group ID |
| `metadata` | `app_version`, exact lowercase 40-hex `commit`, opaque `draft_project`, integer `draft_revision`, `device` model, `android` version, `abi`, `codec` decoder identity |
| `scenario` | `style`, `aspect`, `mode`, `input_class` strings; `export_fps` integer 30 or 60 |
| `sources` | Ordered array of one or two source profile objects, kept separate in report |
| `events` | Every raw request, including failed/missing/incomplete/superseded requests |
| `playback` | Observed playback passes with raw 30 FPS grid slots |
| `scrubbing` | Observed scrubbing sessions; no overlaps |
| `memory` | Same-timestamp category observations |

Each source: lowercase 64-hex `sha256`; `codec` string (`h264` for baseline),
positive integers `width`, `height`, `fps`, `gop_us`, `sar_num`, `sar_den`;
`dynamic_range` string (`SDR` for baseline); integer `rotation` 0/90/180/270.
Baseline means **every** source is H.264 SDR, 1920×1080 or 1080×1920, 30/60 FPS,
GOP ≤2,000,000 us. Other profiles (HEVC, 4K, long GOP, etc.) get separate heavy
rows, never pooled with baseline. Integer nominal source FPS is this v1 format;
future fractional-rate support requires an explicit schema revision.

Do not serialize serial numbers, paths, private filenames, videos, biometric
payloads or user identities. `draft_project` is an opaque anonymized ID. Input
is preview output-clock evidence; encoder/export timing datasets do not belong
here. No source endpoint is inferred from source duration, first PTS or output
PTS. Legitimate repeated **source** PTS during speed ramps are unrelated to
duplicate shown **output** PTS; source PTS are not event/playback fields.

## Raw events

| Field | Type / meaning |
| --- | --- |
| `id`, `event` | Opaque unique token; FIRST_FRAME / CACHED_SCRUB / EXACT_SEEK / FORMAT_CHANGE / PLAY_START |
| `request_ns`, `start_ns`, `end_ns` | Monotonic ns; request ≤ start ≤ end; end may be null for missing/incomplete requests |
| `requested_output_us`, `shown_output_us` | Raw requested output target; actual presented canonical output PTS (nullable) |
| `generation` | Operation epoch object `{project, surface, seek}` with integer fields |
| `completion_generation` | Current epoch **at completion/presentation**, same shape or null when unknown |
| `status` | completed / failed / missing / incomplete / obsolete / superseded |
| `reason` | Nonempty reason for non-completed status; nullable for completed |
| `presented`, `canonical_ready`, `exact` | JSON booleans; actual presentation, all required layers canonical-ready, exact canonical completion |
| `temperature` | cold/warm for FIRST_FRAME; null for all other events |
| `prepare_ns` | Preparation duration separate from FIRST_FRAME; nullable for FIRST_FRAME and null for other events |

FIRST_FRAME `start_ns` means draft and Surface are ready, after preparation;
FORMAT_CHANGE means format request; PLAY_START means play request. End requires
the corresponding canonical-ready presentation. CACHED_SCRUB records the
intermediate cached response; a thumbnail cannot complete EXACT_SEEK.
EXACT_SEEK latency uses **end − request**, covering all work since the last
canonical request/scrub release; a delayed internal start cannot hide latency.
Other events use end − start. The producer must keep these semantic boundaries.
The calculator cannot detect an omitted request or dishonest readiness flag.

Completed timing requires end, presentation PTS and completion epoch. For every
event except CACHED_SCRUB, canonical_ready and exact must also be true. A claimed
completed nonexact/thumbnail response is FAIL. Unknown completion evidence is
INCOMPLETE. A successful/presented callback under a different completion epoch
is obsolete FAIL and excluded from successful timing/quotas. **Later** epochs
elsewhere in the file do not invalidate historical successful completions.

An intentionally ignored intermediate EXACT_SEEK may be `superseded` only with
`presented=false` and `shown_output_us=null`. Its count/reason stays visible;
it contributes neither timing nor quota and does not alone block PASS. A
superseded request carrying presentation evidence is obsolete FAIL. Failed,
missing, incomplete final requests still block PASS; do not relabel them.

p95 is nearest-rank: sorted valid completed durations `[ceil(.95*N)-1]`.
FIRST_FRAME cold and warm distributions (including preparation) stay separate.
Quotas and p95 limits apply to each independent group:

| Event | Completed quota | p95 limit (ms) |
| --- | --- | --- |
| FIRST_FRAME cold / warm | 20 each | 2000 each |
| EXACT_SEEK | 100 | 750 |
| FORMAT_CHANGE | 20 | 150 |
| CACHED_SCRUB | At least one observed sample | 100 |
| PLAY_START | At least one observed sample | 300 |

No additional scrub/play-start count quota is invented. Accuracy reports
absolute raw-requested/shown **output** PTS error separately; it must be ≤one
canonical export frame (`error_us * export_fps <= 1,000,000`). Cached intermediate
responses are excluded from canonical/paused accuracy. The producer snaps raw
targets to canonical export frames; the parser does not infer snapping or
require raw requested PTS equality. For example, a raw 20,000 us request shown
as canonical 0 us with exact=true is within one 30 FPS frame and remains a valid
timing; 33,334 us error at 30 FPS fails independent accuracy and group PASS.

## Playback and coverage

A playback pass has `id`, positive `observed_duration_us`, positive
`recipe_duration_us`, boolean `completed`, and `frames` array. Duration is
actually observed, not merely the requested recipe length. Each pass starts at
output time zero. Its expected 30 FPS grid comprises indices
`0 .. ceil(observed_duration_us*30/1,000,000)-1`; scheduled output time is
`floor(index*1,000,000/30)` us. A frame is `{index, status, shown_output_us,
audio_us}`; status is shown/dropped/missing, and both clocks are nullable.
The valid audio clock must be observed at that presentation. Non-shown slots
carry null clocks. Absent indices count as missing; duplicate indices or shown
output PTS within a pass are invalid, never extra shown/draw-call samples.

Drops are dropped expected grid slots / expected slots, limit ≤5%; missing
slots cannot pass. A/V error is `abs(shown_output_us-audio_us)` ≤50,000 us. Null
clock means unknown drift/INCOMPLETE. Both aggregate and **per-pass** counts,
drop percentages, max drift and statuses are reported; a failing pass cannot be
hidden by other passes. No observed playback means INCOMPLETE.

A full play has completed=true, observed duration ≥recipe duration, and no
missing grid slots. Coverage requires ≥3 full plays per group's style/scenario.
Each scrub session is `{id, start_ns, end_ns, observed_duration_us, completed}`;
observed duration cannot exceed the monotonic interval. Nonoverlapping completed
sessions must total ≥600,000,000 us (10 minutes). Uncompleted sessions remain
visible and block coverage PASS. These records prepare later physical evidence;
synthetic clock arithmetic proves no physical duration, drift or viewing.

## Memory

Each sample is `{at_ns, app_bytes, process_bytes}`. `app_bytes` categories are
`cache`, `pages`, `header`, `track`, `scratch`, `thumbnail`, `fbo`; these are
disjoint accounting buckets across **all sources**, not a limit per source.
For each complete timestamp, sum these categories; app peak is the maximum of
those concurrent totals, limit 33,554,432 bytes (32 MiB). Independent category
maxima are reported separately and are never summed as a fabricated peak.

`process_bytes` categories: `rss`, `java`, `native`, `gpu`, `codec`. These overlap
and remain separate observations, never added to the app's 32 MiB budget. A
large MediaCodec measurement alone does not violate the app budget.
Every value is a nonnegative integer or explicit null. Missing category keys
are tolerated as unmeasured evidence and listed, just like null. Any missing
observation makes memory INCOMPLETE; known maxima remain partial observations.
No complete app sample yields unknown peak, not zero. Empty memory is INCOMPLETE.

## Minimal synthetic exchange example

This structurally valid fixture intentionally lacks quotas/playback/memory and
reports INCOMPLETE. It is not a real measurement or acceptance matrix.

```json
{
  "schema_version": 1,
  "groups": [{
    "id": "synthetic-example",
    "metadata": {
      "app_version": "synthetic", "commit": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "draft_project": "anonymous-project", "draft_revision": 1,
      "device": "synthetic-arm64", "android": "36", "abi": "arm64-v8a", "codec": "synthetic-decoder"
    },
    "scenario": {"style": "FEAR", "aspect": "1:1", "mode": "manual", "input_class": "synthetic", "export_fps": 30},
    "sources": [{"sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "codec": "h264", "width": 1920, "height": 1080, "fps": 30, "dynamic_range": "SDR", "gop_us": 2000000, "rotation": 0, "sar_num": 1, "sar_den": 1}],
    "events": [{
      "id": "request-1", "event": "EXACT_SEEK", "request_ns": 1000, "start_ns": 1000, "end_ns": 10001000,
      "requested_output_us": 0, "shown_output_us": 0,
      "generation": {"project": 1, "surface": 1, "seek": 1},
      "completion_generation": {"project": 1, "surface": 1, "seek": 1},
      "status": "completed", "reason": null, "presented": true,
      "canonical_ready": true, "exact": true, "temperature": null, "prepare_ns": null
    }],
    "playback": [], "scrubbing": [], "memory": []
  }]
}
```
