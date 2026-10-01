"""Run the complete offline Python suite; never treat skipped or missing tests as green."""

import argparse
import json
from pathlib import Path
import sys
import unittest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start-directory", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.discover(str(args.start_directory), pattern="test_*.py")
    discovered = suite.countTestCases()
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    passed = (discovered > 0 and result.testsRun == discovered and
              result.wasSuccessful() and not result.skipped)
    report = {
        "discovered": discovered, "run": result.testsRun,
        "failures": len(result.failures), "errors": len(result.errors),
        "skipped": len(result.skipped), "passed": passed,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if not discovered:
        print("ERROR: no tests discovered", file=sys.stderr)
    if result.skipped:
        print("ERROR: skipped tests do not satisfy the complete suite", file=sys.stderr)
    if result.testsRun != discovered:
        print("ERROR: the runner did not execute every discovered test", file=sys.stderr)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
