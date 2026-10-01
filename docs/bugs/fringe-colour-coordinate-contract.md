# Fringe colour samples use a different coordinate contract

Observed defect: opening hair boundary has a light contaminated halo. This
change does not claim to fix alpha holes or model hair classification.

The re-entry shader computes its mask gradient in top-down bitmap coordinates,
but previously added that offset directly to transformed decoder texture UV.
Subject RGB itself uses a bitmap Y flip followed by `uIncomingTexMatrix`.
The two sampling paths therefore disagree for rotated/cropped/transformed input.
For transforms cancelling the Y flip and having unit scale they may coincide;
do not assume this explains every observed halo.

The neighbouring mask point now follows exactly the same flip and texture
matrix as the central subject point. This is an isolated coordinate correction,
not a new algorithm. Requires a fresh emulator MP4 comparison before a visual
improvement can be claimed. The ongoing `face-interior-g1-0907` predates this
change and must not be used as its device verification.

Verification: `testDebugUnitTest --rerun-tasks` executed successfully, followed
by successful `testDebugUnitTest lintDebug assembleDebug` after adding the
shader-source contract guard. The guard requires bitmap-Y conversion plus the
decoder matrix and rejects direct mask-offset addition to decoder UV. It does
not execute GLSL and is not evidence of a corrected halo. APK archive inspection
found no MP4/Golden fixtures; `git diff --check` passed.
