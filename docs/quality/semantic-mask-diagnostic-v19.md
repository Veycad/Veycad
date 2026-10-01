# Raw/refined segmentation diagnostic — 2026-09-27

Diagnostic only; no production mode/model/threshold/style/cache change. Build and
runtime execution are not established by this document.

The completed v17 Coconut cache contains121 refined masks, all256x256. Read-only
attachment audit SHA `da23b08bdaf97b4828b839baa08fc354ef2b9aa20fbfe0e9302890d61a52e0e2`
(`calibration_v17_cached_mask_audit_20260927.json`) identifies42 low-confidence
masks as nearly empty, none nearly full:30 have zero foreground pixels at the
actual .5 foreground threshold;34 still have cached human confidence>=.55.
Cache SHA `f4d8e3809e6f204f663900f2be969cf48ddd264b14417fadfc52dee5712f78f9`.
This distinguishes empty-vs-full failure, not raw model vs refinement causality.

Full source analysis uses frame-only MulticlassMatteProvider (targets=null), then
ML Kit SelfieSegmenter STREAM_MODE. It does not use the six-class MediaPipe model.
Google documents [multiple people/full body support](https://developers.google.com/ml-kit/vision/selfie-segmentation),
so the name "selfie" is not proof that the material is an unsupported class.
[STREAM mode uses previous results; SINGLE mode is independent](https://developers.google.com/ml-kit/vision/selfie-segmentation/android).
Sparse temporal state failure remains a hypothesis, not a reason to change production.

New debug flag `semantic_mask_probe=true` requires a completed existing cache17,
re-decodes the entire ordered actual PTS sequence using the existing frame-only
provider, and requires all PTS to match that cache. Every same immutable bitmap
is supplied to fresh STREAM and SINGLE segmenters. No selected-frame prefix is
substituted for production's complete ordered STREAM prefix. A STREAM inference
failure marks its remainder unknown instead of pretending that history survived.

Every successful row reports raw mask, direct256x256 downsample and guidedRefine
statistics: native dimensions, min/max/mean, foreground pixel count/coverage,
existing SemanticMaskMetrics separation/confidence and elapsed time. Failure is
unknown/null, not zero. Bitmap dimensions, actual PTS/index and an ARGB pixel hash
bind both detector modes to the same decoded input. Existing cached refined masks
and human scalars are explicitly reference-only. Production cache is direct-read
without changing mtime/deleting malformed data; its byte hash must stay unchanged.

Fresh SINGLE_IMAGE_MODE pose reports all current landmarks/type/coordinates and
inFrameLikelihood without filtering them into invented complete-body evidence.
[That mode has no person tracking](https://developers.google.com/ml-kit/vision/pose-detection/android);
on four dancers it may switch identity. The report supplies no validated joint
motion, all-people mask ownership, camera compensation or alternate FEAR branch.

Root will preregister `calibration_v19_semantic_mask_probe_20260927.json[.result]`
before frozen build/install. Golden's new branch exclusively claims fresh output
and marker, records runtime/source/cache identities and probe-only status, and
never deletes an existing diagnostic. No MP4/director/acceptance code is invoked.

Discriminating interpretation after execution: raw empty in both modes suggests
model/input limitations; raw valid but refined empty isolates resampling/refinement;
STREAM empty but SINGLE valid suggests temporal-state sensitivity. None alone
proves human-mask accuracy. Actual per-person mask annotations and independently
labelled background/limb displacement remain needed to assess estimator accuracy,
moving-audience contamination, search range, class/identity at both endpoints and
whole-body weighting. Do not lower .18 or coverage/confidence gates to make this
disclosed challenge pass; no unseen holdout is consumed or relabelled.
