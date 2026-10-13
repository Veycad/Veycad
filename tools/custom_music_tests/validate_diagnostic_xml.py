"""Fail a diagnostic phase even when am instrument returns zero for a JUnit failure."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def validate(path: Path, class_name: str):
    root = ET.parse(path).getroot()
    if root.tag != "testsuite" or any(root.get(k) != "0" for k in ("failures", "errors", "skipped")):
        raise ValueError("Diagnostic XML contains failure/error/skip")
    cases = root.findall("testcase")
    if root.get("tests") != "1" or root.get("executed") != "1" or len(cases) != 1:
        raise ValueError("Exactly one executed diagnostic test required")
    if cases[0].get("classname") != class_name or any(cases[0].find(k) is not None
                                                    for k in ("failure", "error", "skipped")):
        raise ValueError("Unexpected diagnostic identity/result")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("class_name")
    args = parser.parse_args()
    validate(args.path, args.class_name)
