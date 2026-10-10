import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("validate_diagnostic", Path(__file__).with_name("validate_diagnostic_xml.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


class DiagnosticXmlTest(unittest.TestCase):
    def check(self, attrs='', body='<testcase classname="diagnostic.Class" name="decode"/>'):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'report.xml'
            path.write_text('<testsuite tests="1" executed="1" failures="0" errors="0" skipped="0" ' +
                            attrs + '>' + body + '</testsuite>')
            api.validate(path, 'diagnostic.Class')

    def test_single_diagnostic_pass(self):
        self.check()

    def test_failure_child_is_rejected_even_when_summary_is_green(self):
        with self.assertRaises(ValueError):
            self.check(body='<testcase classname="diagnostic.Class"><failure/></testcase>')

    def test_missing_case_and_wrong_class_are_rejected(self):
        for body in ['', '<testcase classname="other.Class"/>']:
            with self.assertRaises(ValueError):
                self.check(body=body)

    def test_skip_and_duplicate_case_are_rejected(self):
        for body in ['<testcase classname="diagnostic.Class"><skipped/></testcase>',
                     '<testcase classname="diagnostic.Class"/><testcase classname="diagnostic.Class"/>']:
            with self.assertRaises(ValueError):
                self.check(body=body)


if __name__ == '__main__':
    unittest.main()
