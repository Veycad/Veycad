"""Independent standard-library JSON check of the small host model fixture."""
import json
import sys
from decimal import Decimal
from pathlib import Path

with Path(sys.argv[1]).open(encoding="utf-8") as source:
    data = json.load(source, parse_float=Decimal)

graph = data["graph"]
assert set(graph) == {
    "audioTrack", "clips", "effectGraph", "frameAttachments", "metadata",
    "outputDurationMs", "overlays", "parameterTracks", "profile", "sourceDurationMs", "version",
}
assert graph["audioTrack"]["sourceStartUs"] == 15_000_000
assert graph["metadata"]["parentRevision"] is None
assert graph["clips"][0]["transitionIn"] == "OPEN"
assert graph["clips"][0]["transitionDurationMs"] is None
plane = graph["frameAttachments"]["frames"][0]
assert plane["depth"] is None and plane["sourceTimeUs"] == 123_456
assert plane["mask"]["values"] == [Decimal("0.25"), Decimal("0.75")]

acceptance = data["acceptance"]
assert data["quality_gate"] is False and acceptance["accepted"] is False
assert acceptance["issues"] == ["audio-unclamped-headroom-unavailable", "colour-jump"]
assert acceptance["decodedAudio"]["unclampedFloatEvidence"] is False
assert acceptance["decodedAudio"]["issues"] == ["audio-unclamped-headroom-unavailable"]
assert set(acceptance["metrics"]) == {
    "beatHitRate", "repeatedSourceRatio", "transitionPeak", "avDriftUs", "maximumArtifactScore",
    "maximumColourJump", "faceLossRate", "maximumEdgeLeakRatio", "minimumMaskTemporalIou",
    "maximumBlackBlockScore", "referenceGrammarFit", "maximumColourJumpTimeUs", "durationErrorUs",
    "authorAccentHitRate", "maximumAuthorAccentOffsetUs", "referenceTimelineRecall",
    "decodedDoubleExposurePeak", "decodedMirrorSlicePeak", "decodedGlitchPeak",
    "maximumSubjectStageBackgroundLuma", "maximumSubjectStageBackgroundLoss",
    "videoStartPtsUs", "audioStartPtsUs",
}
assert acceptance["metrics"]["maximumColourJumpTimeUs"] == 222_222
assert acceptance["metrics"]["transitionPeak"] == Decimal("0.12")
assert acceptance["reference"]["minimumTransitionPeak"] == Decimal("0.16")
assert data["visual_samples"][0]["outputTimeUs"] == 222_222
assert data["visual_samples"][0]["decodedFaceEvidence"] is None
assert data["audio_map"]["beats"][0]["isDownbeat"] is True
assert data["audio_map"]["onsets"][0]["dominantBand"] == "HIGH"
assert data["audio_map"]["drops"][0]["strength"] == Decimal("0.8")
event = data["visual_map"]["events"][0]
assert event["type"] == "CAMERA_MOVE" and event["direction"] == "RIGHT"
assert event["confidence"] == Decimal("0.8") and event["usableWindow"]["endUs"] == 200_000
assert data["decoded_source_clock"] == {"222222": 123_456, "9007199254740993": -1}
assert data["scalars"]["long"] == 9_007_199_254_740_993
assert data["scalars"]["double"] == Decimal("0.12345678901234566")
assert data["scalars"]["text"] == "Музыка 🎵\n\0\"\\"
assert data["scalars"]["surrogates"] == "\ud800x\udfff"
print("Independent JSON validity and complete model payload fidelity: passed")
