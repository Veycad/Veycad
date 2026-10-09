# Automated test review — 2026-09-27

Reviewed every automated test file currently in the working copy: **77 Kotlin
files and 18 Python files**. Compared changes against the saved pre-review working
copy, preserving the application's pre-existing uncommitted work. All reviewers
read the relevant implementation contracts alongside the tests. An independent
review checked the changes, execution gates and mutation isolation.

The initial runnable snapshot contained **578 JVM + 220 Python** methods. The
earlier chat's Python count of 205 became stale while another authorized chat
added inspector tests. Concurrent temporal work added a new Kotlin class and
Python cases during this review; those changes were preserved and also reviewed.
The final coherent inventory is **595 JVM + 242 Python = 837 methods**. More
methods are not themselves evidence of better tests; fixture subcases are not
counted separately.

## Why green reports were insufficient

- `baselineVerify` and CI ran Android tests but omitted the complete Python suite.
- Some expected values came from the same production helper being tested. A
  shared clock, depth, geometry or grade bug could therefore pass both sides.
- Some loops used conditional assertions or `all` on potentially empty results,
  allowing missing measurement/support to pass.
- Cache-header refusal tests reused the same check and used incomplete files;
  a later parser failure could hide incorrectly accepted headers.
- Several expensive Duality scenarios rebuilt the same montage unnecessarily.
- JVM tests named "rendered" or "decoded" generally test pure logic using
  synthetic arrays/metrics. They do not perform Android rendering or decoding.

## Changes and preserved coverage

**43 Kotlin files and all 18 Python files changed** relative to the saved review
snapshot; one of the Kotlin files was introduced by concurrent work. Per-file
decisions, exact removed methods and preserved cases are recorded in:

- [Motion, perception and caches — 32 files](motion-review.md).
- [Montage, audio and rendering contracts — 32 files](montage-review.md).
- [State, storage, source rules, UI logic and concurrent temporal evidence — 13 files](application-review.md).
- [Python tools — 18 files](python-review.md).

Fourteen cache/profile-version refusal methods now use one complete-payload
matrix, retaining all previously checked versions. Three Duality moving-edit
methods became one scenario retaining their assertions and building two montages
instead of four. Two acceptance duplicates and five Python duplicates were
removed with their cases retained elsewhere. This reduces 22 redundant method
slots; new independent boundaries and state transitions explain the separate
increase in final method counts.

Expected byte values, durations, pulse/tail clocks, geometry and physical grade
targets now come from independently specified contracts. Assertions now require
actual support where the fixture establishes it, check each vector or relevant
frame, and cover negative controls, exact thresholds and ordered state changes.
Temporary files are cleaned automatically. Shared Python factories return fresh
objects and no longer import fixtures from test modules. Small synthetic inspector
fixtures retain structural/temporal cases while reducing deep-copy overhead.

Two actual production defects were reproduced and fixed:

1. The semantic support tool's valid `margin=0` called Pillow `MaxFilter(1)` and
   terminated the Windows process with `0xC0000094`. The zero-margin identity
   dilation now bypasses that native call; its regression runs in a subprocess.
2. The Android matte-cache reader accepted an incomplete final timestamp after a
   valid frame. The new test failed before the fix. The reader now stops only
   between records, and all 1..7 trailing-byte cases are rejected.

The parallel temporal inspector work also exposed a malformed-kind TypeError.
Its external author's type guard was preserved; this review does not claim that
fix or the temporal runtime changes as its own work.

## Execution evidence

| Check | Result |
| --- | --- |
| Complete JVM suite | 595 tests / 77 suites; 0 failures, errors or skips |
| Complete Python suite | 242 discovered and executed; 0 failures, errors or skips |
| Selected JVM production mutations | 9/9 detected by assertion failures |
| Selected Python production mutations | 5/5 detected by assertion failures; 0 setup/import errors |
| Python runner controls | Empty, skipped, assertion failure, import error and premature stop rejected; genuine success accepted |
| Android lint | 0 errors, 13 existing warnings |
| Release manifest/source security contract | Passed |
| Debug APK assembly | Passed; no installation, upload or device export performed by this review |

The first full run caught an invalid empty-map fixture; it was corrected to a
valid nonempty observation without human evidence. The Duality fixture's nearby
music cues also required distinguishing eligible cues from protected cut margins.
Neither fix lowered production gates. The first mutation run failed when a
target omitted Kotlin's `$app` method suffix; that runner error was corrected and
was not counted as a detected regression.

The last full JVM run recorded 71.379 seconds summed across its test classes,
versus 89.205 seconds in the archived original report. Duality recorded 18.028
versus 30.427 seconds. These are single local observations with different
workloads and machine load, not a controlled performance benchmark.

Local reproducibility evidence is in `build/reports/`:
`test-review-inventory.json`, `python-tests.json`, `test-mutations.json`,
`python-test-mutations.json` and `test-runner-controls.json`. The inventory records
every current test file's count, SHA-256 and removed/renamed method names; no test
filename is missing from the per-file reviews. Full build logs and the failing
cache regression are under `artifacts/testing/review-20260927/`. The ordinary JVM
XML/HTML reports remain in `app/build/test-results/` and `app/build/reports/tests/`.

## Repeatable execution

`baselineVerify` now includes both complete suites. Missing Python dependencies,
empty discovery, skips or incomplete Python execution fail the gate. JVM tests
also reject empty discovery and skipped cases; a no-source task cannot satisfy
the baseline. CI installs Python test dependencies and saves both suites' reports.

On Windows, set `JAVA_HOME` and `PYTHON` as described in [WINDOWS.md](../../WINDOWS.md),
then run:

```powershell
.\gradlew.bat baselineVerify
.\gradlew.bat testMutationCheck pythonMutationCheck
```

Both mutation checks alter only isolated in-memory copies; application/tool
source and compiled class files are never patched. Original targeted tests must
pass first. JVM class-loading/verification errors, skips and incomplete runs do
not count as caught bugs. The selected mutations check style state, stale music
callbacks, duration/human gates, mask byte normalization, headroom, depth, measured
Heartbeat grade and tail timing; Python checks support, byte ranges, RMS, dates
and missing clocks. This is a sample of realistic defects, not a whole-suite
mutation score or a guarantee that every possible regression is detected.

The principal remaining integration gap is unchanged: no `androidTest` UI/device
suite verifies actual touch flows, Activity lifecycle, ML/provider IPC, codecs,
GLES output and real MP4 export together. Real-media acceptance requires actual
device output and independent decoding; synthetic green reports cannot establish
perceptual quality or replace human review.
