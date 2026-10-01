# Heartbeat: unmeasured transition cycle — 2026-09-07

Found a modulo-index WHIP/OCCLUSION/BLACKOUT sequence in HeartbeatDirector,
plus constant sideways translation on whip slots. These choices had no
reference measurement. Reviewed local cut-pairs and newly decoded checkpoints
in transition-review-0907: early scene changes are concealed by brief flashes;
later checked entries show defocus. This does not establish every transition's
complete motion curve.

Removed the modulo sequence and fixed sideways transforms. Authored cut times,
26 measured pulses, 22 defocus nodes and 11 echo layers remain. Added a
regression test preserving those accents while excluding invented wipes and
sideways crops. Unit tests, lintDebug and assembleDebug passed. APK archive
scan found no MP4/Golden/reference-video fixtures.

Launched emulator run heartbeat-authored-cuts-g0-0907 with Golden 0 and licensed
heartbeat-author.m4a, synchronous echo. No result marker at the last check;
do not claim visual improvement or completion. Prior process PID 12014.

User then requested Samsung installation: adb install -r succeeded on SM_A256E,
serial R5CX109MBJF. Heartbeat was already available in the current catalog.
Selected it through the installed modal and applied; UI dump confirmed
`Выбрать стиль · Heartbeat`. Existing My Edits count was 7 after update.
No application data was cleared. This verifies availability, not full style QA.
