# Heartbeat disk-blur regression

Same installed APK for all three sources, before the unverified separate echo
branch and before HeartbeatPulseAudit instrumentation.

Golden 0 and Golden 1 completed; each 1271 frames at 60 fps, 21.1833 s container
duration, audio present, last-PTS A/V drift 13,288 us. MP4 and reports are under
`artifacts/reference-analysis/heartbeat/heartbeat-diskblur-g{0,1}-0907*`.
Golden 1 decoded transition sheet at `diskblur-g1/sheet-0.jpg` shows live gesture
motion, defocus at 3.733/4.233 s and restored detail at 4.000/4.500 s, without
the grid-kernel collar arcs at these checkpoints. Temporal all-frame acceptance
is still pending. Generic QA rejects both edits; its repeated-source and author
metrics are not Heartbeat-specific proof. Beat alignment is still unfinished.

Golden 2 completed as `heartbeat-diskblur-g2-0907` with that same installed APK:
1271 frames, audio present, 13,288 us last-PTS drift, 13,288,868 bytes.
Report and inspector are saved locally. The 12-frame sheet at `diskblur-g2/sheet-0.jpg`
shows blur resolving at both checked cuts (3.733 and 4.233 s), with detail restored
at 4.000 and 4.500 s. This is still-frame evidence, not complete temporal acceptance.
Generic quality acceptance is false on all three sources.

Prepared a separate decoded pulse audit: explicit missing versus wrong-luma
events, using samples inside each measured pulse window, not nearest arbitrary
frames. Its success will NOT override generic acceptance or certify musical
rhythm. Added two unit tests; unit/lint/build pass. The three-source runs above
predate this reporting and must not be presented as validating it.

Fixed sample-target comparison to accept exactly one microsecond of decoder
truncation, in both decode selection and target advancement. Tests reject larger
early offsets and cover integer extremes. Unit/lint/build pass; device validation
is pending in `heartbeat-echo-g0-0907`, launched after Golden 2 finished. That run
also includes the separate experimental echo branch, so it must not be pooled
with the same-APK disk-blur regression above.

The echo run subsequently completed: all 26 pulses measured and matched, zero
missing/wrong-luma events. Export and report are local. This validates the
sampling/audit path on this render, not overall visual acceptance (still false).
Full unit tests were rerun without cache successfully; lint/build pass and APK
archive listing contains no Golden/reference MP4s. Echo visual assessment is
recorded in `docs/bugs/heartbeat-weak-echo.md`; it is not an accepted improvement.
