# DUALITY two-source RAM regression plan

> Execute inline with `superpowers:executing-plans`; request one fresh review at the end.

**Goal:** Measure two 60-second sources with a 192 MiB Android heap, fix the confirmed retention risk, verify and update PR #65 without merging.

**Design:** DUALITY consumes observations, not render planes. Add an observation-only source capability with its own cache identity. Keep semantic inference and observation timestamps/values unchanged; do not retain its per-frame mask/depth/flow arrays. Preserve the existing full editorial and correspondence profiles for their consumers.

- [x] Pin PR head/base and inspect all profile consumers, caches and DUALITY render inputs.
- [x] Add a failing capability regression and an optional debug-only heap/native/PSS sampler.
- [x] Run two independent 60-second derivatives of existing fixtures in a task-owned API 36 AVD with heap growth limit 192 MiB; preserve original media.
- [x] Reproduce without the sampler: the MLHandler thread throws OutOfMemoryError at the 192 MiB growth limit.
- [x] Implement observation-only DUALITY analysis and cache validation; test capability boundaries and cache round-trip (49 targeted JVM tests pass).
- [x] Add real two-source 60-second cold/warm analysis instrumentation with zero retained planes; the focused device test passes.
- [x] Measure cold and warm native DUALITY export on the same inputs/heap; retain CSV, logs and MP4 checks. Both complete; Java peaks 38.73/42.50 MiB.
- [x] Run clean baseline (1023 JVM + 290 Python) and applicable Android checks.
- Publication gates: one fresh independent review, scoped commit/push, exact-head CI, and PR #65 evidence/limitations. Record these final statuses in the PR, avoiding self-referential verification commits. Never merge.

**Ruling:** Existing fixture videos are read only and looped/transcoded into a separate evidence folder. No user video or build artifact enters the commit.

**Limits:** Emulator PSS/native allocations and Java heap are separate metrics; sampled peaks are lower bounds. This does not establish physical ARM/OEM behavior or artistic acceptance.
