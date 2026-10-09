"""Verify selected tests reject realistic defects, using in-memory clones only.

This is a sampled assertion sensitivity check, never a whole-suite mutation score.
No source files, approved media, authorization records or test reports are patched.
"""

import argparse
import hashlib
import importlib
import inspect
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch


CASES = (
    ("semantic-support-no-op", "probe_semantic_alpha_support", "constrain",
     "return alpha * support", "return alpha",
     "test_semantic_alpha_support", "SupportTest", "test_support_never_invents_foreground"),
    ("range-ignores-start", "heartbeat_review_server", "byte_range",
     "start = int(match[1])", "start = 0",
     "test_heartbeat_review_server", "RangeTests", "test_browser_seek"),
    ("audio-rms-zero", "quality_audio_report", "assess_pcm",
     "math.sqrt(square_sum / len(samples)) if samples else 0.0", "0.0",
     "test_quality_audio_report", "QualityAudioReportTest", "test_pcm_statistics_use_every_channel_and_the_frame_clock"),
    ("future-review-accepted", "quality_review_identity", "valid_review_identity",
     "reviewed <= current", "True",
     "test_quality_review_identity", "QualityReviewIdentityTest", "test_future_times_are_rejected_after_offset_normalisation"),
    ("missing-clocks-dropped", "compare_render_clocks", "compare",
     "missing.append(time)", "pass",
     "test_compare_render_clocks", "RenderClockTest", "test_partition_sorted_union_and_primary_decoder_changes_without_mutating_input"),
)


def source_snapshot():
    root = Path(__file__).resolve().parent
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(root.glob("*.py"))}


def run_case(case):
    name, module_name, function_name, before, after, test_name, class_name, method = case
    module = importlib.import_module(module_name)
    test_module = importlib.import_module(test_name)
    test_class = getattr(test_module, class_name)

    def run():
        return unittest.TextTestRunner(stream=io.StringIO()).run(
            unittest.TestSuite([test_class(method)]))

    baseline = run()
    source = inspect.getsource(getattr(module, function_name))
    if source.count(before) != 1:
        raise ValueError(f"{name}: mutation target changed; expected exactly one expression")
    namespace = dict(module.__dict__)
    exec(compile(source.replace(before, after), f"<mutation:{name}>", "exec"), namespace)
    # Test imports a changed copy of production; original modules remain intact.
    with patch.object(test_module, function_name, namespace[function_name]):
        mutated = run()
    return {"mutation": name, "test": f"{test_name}.{class_name}.{method}",
            "baseline_passed": baseline.wasSuccessful() and not baseline.skipped,
            "mutant_assertion_failures": len(mutated.failures),
            "mutant_errors": len(mutated.errors),
            "detected": bool(mutated.failures) and not mutated.errors and not mutated.skipped}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    before = source_snapshot()
    cases = [run_case(case) for case in CASES]
    after = source_snapshot()
    passed = before == after and all(case["baseline_passed"] and case["detected"] for case in cases)
    report = {"scope": "five selected production-expression mutants; not a full mutation score",
              "passed": passed, "source_files_unchanged": before == after,
              "mutations": cases, "source_sha256": after}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "source_sha256"}, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
