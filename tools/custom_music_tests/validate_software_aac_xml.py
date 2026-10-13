"""Require the exact three software diagnostic cases; instrumentation exit is insufficient."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

CLASS = 'com.veycad.app.CustomMusicSoftwareAudioProbeTest'
METHODS = {'native_mp4', 'overshoot', 'integer_cast'}


def validate(path):
    root = ET.parse(path).getroot()
    if root.tag != 'testsuite' or any(root.get(key) != '0' for key in ('failures', 'errors', 'skipped')):
        raise ValueError('Diagnostic failure/error/skip')
    cases = root.findall('testcase')
    if root.get('tests') != '3' or root.get('executed') != '3' or len(cases) != 3:
        raise ValueError('Exactly three executed software diagnostic cases required')
    if {case.get('name') for case in cases} != METHODS or any(case.get('classname') != CLASS or
            any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')) for case in cases):
        raise ValueError('Unexpected diagnostic case identity/result')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path', type=Path)
    validate(parser.parse_args().path)
