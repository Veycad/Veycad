import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from quality_render_report import REVIEW_AREAS, STYLE_REVIEW_AREAS, assess
from quality_review_form import create, create_negative, prepare, prepare_negative
from quality_test_support import heartbeat_result


class QualityReviewFormTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "synthetic.mp4"
        self.output.write_bytes(b"synthetic fixture, not real video")
        self.result = self.root / "synthetic.result"
        self.fields = heartbeat_result()
        self.fields["output_sha256"] = hashlib.sha256(self.output.read_bytes()).hexdigest()
        self.save()

    def save(self):
        self.result.write_text("\n".join(f"{key}={value}" for key, value in self.fields.items()),
                               encoding="utf-8")

    def test_each_product_form_has_only_unreviewed_judgements(self):
        for recipe, areas in STYLE_REVIEW_AREAS.items():
            with self.subTest(recipe=recipe):
                self.fields["recipe"] = recipe
                self.save()
                form = prepare(self.result, self.output)
                self.assertEqual(form["recipe"], recipe)
                self.assertEqual(form["output_sha256"], self.fields["output_sha256"])
                self.assertEqual(form["checks"], dict.fromkeys(REVIEW_AREAS, False))
                self.assertEqual(form["style_checks"], dict.fromkeys(areas, False))
                self.assertEqual(form["reviewer"], "")
                self.assertEqual(form["reviewed_at"], "")
                self.assertIs(form["playback_1x"], False)
                self.assertIs(form["playback_half"], False)
                self.assertFalse(assess(self.fields, recipe, form)["machine_and_human_pass"])

    def test_changed_output_or_incomplete_report_cannot_create_form(self):
        for field, value in (("status", "failed"), ("recipe", "unknown"),
                             ("graph_generator", ""), ("output_sha256", "a" * 64)):
            original = dict(self.fields)
            with self.subTest(field=field):
                self.fields[field] = value
                self.save()
                with self.assertRaises(ValueError):
                    prepare(self.result, self.output)
            self.fields = original
        self.save()
        self.output.write_bytes(b"changed fixture")
        with self.assertRaises(ValueError):
            prepare(self.result, self.output)

    def test_existing_review_is_preserved(self):
        destination = self.root / "review.json"
        create(self.result, self.output, destination)
        original = destination.read_bytes()
        with self.assertRaises(FileExistsError):
            create(self.result, self.output, destination)
        self.assertEqual(destination.read_bytes(), original)
        self.assertEqual(json.loads(original)["reviewer"], "")

    def test_failed_acceptance_does_not_forge_success(self):
        self.fields.update(acceptance="false", acceptance_issues="face-loss")
        self.save()
        form = prepare(self.result, self.output)
        self.assertFalse(assess(self.fields, "HEARTBEAT", form)["machine_and_human_pass"])

    def negative_result(self, recipe="SIGMA"):
        path = self.root / "refused.mp4.result"
        fields = {"status": "material_rejected", "recipe": recipe,
                  "source_sha256": "a" * 64, "render_source_sha256": "a" * 64,
                  "static_source": "false", "rejection_code": "insufficient_human_evidence"}
        if recipe == "DUALITY_LOOP":
            fields["secondary_source_sha256"] = "b" * 64
        path.write_text("\n".join(f"{key}={value}" for key, value in fields.items()), encoding="utf-8")
        return path, fields

    def test_negative_forms_bind_exact_result_bytes_and_leave_all_answers_blank(self):
        for recipe in STYLE_REVIEW_AREAS:
            with self.subTest(recipe=recipe):
                path, fields = self.negative_result(recipe)
                payload = path.read_bytes()
                form = prepare_negative(path)
                self.assertEqual(form["recipe"], recipe)
                self.assertEqual(form["source_sha256"], fields["source_sha256"])
                self.assertEqual(form["secondary_source_sha256"], fields.get("secondary_source_sha256"))
                self.assertEqual(form["rejection_code"], fields["rejection_code"])
                self.assertEqual(form["result_sha256"], hashlib.sha256(payload).hexdigest())
                self.assertEqual(form["reviewer"], "")
                self.assertEqual(form["reviewed_at"], "")
                self.assertIs(form["material_case_confirmed"], False)
                self.assertIs(form["message_specific"], False)
                self.assertNotIn("output_sha256", form)

    def test_negative_forms_reject_incomplete_failed_transformed_or_rendered_result(self):
        path, original = self.negative_result()
        for key, changed in (("status", "failed"), ("recipe", "unknown"),
                             ("source_sha256", "not-a-hash"), ("rejection_code", "codec_failure"),
                             ("static_source", "true"), ("render_source_sha256", "b" * 64),
                             ("secondary_source_sha256", "b" * 64)):
            with self.subTest(key=key):
                fields = dict(original, **{key: changed})
                path.write_text("\n".join(f"{k}={v}" for k, v in fields.items()), encoding="utf-8")
                with self.assertRaises(ValueError):
                    prepare_negative(path)
        path, _ = self.negative_result("DUALITY_LOOP")
        path.write_bytes(path.read_bytes().replace(b"secondary_source_sha256=", b"missing_secondary="))
        with self.assertRaises(ValueError):
            prepare_negative(path)
        path, _ = self.negative_result()
        path.with_suffix("").write_bytes(b"stale output must not supply a refusal")
        with self.assertRaises(ValueError):
            prepare_negative(path)

    def test_negative_creation_preserves_existing_review_and_result(self):
        path, _ = self.negative_result()
        payload = path.read_bytes()
        destination = self.root / "refusal.review.json"
        create_negative(path, destination)
        original_review = destination.read_bytes()
        with self.assertRaises(FileExistsError):
            create_negative(path, destination)
        self.assertEqual(destination.read_bytes(), original_review)
        self.assertEqual(path.read_bytes(), payload)
        self.assertEqual(json.loads(original_review)["reviewer"], "")

    def test_negative_cli_creates_only_unreviewed_bound_form(self):
        path, _ = self.negative_result()
        destination = self.root / "cli.review.json"
        command = [sys.executable, str(Path(__file__).with_name("quality_review_form.py")),
                   "--negative", str(path), str(destination)]
        completed = subprocess.run(command, capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(completed.stdout)["reviewed"], False)
        form = json.loads(destination.read_text(encoding="utf-8"))
        self.assertIs(form["material_case_confirmed"], False)
        self.assertEqual(form["result_sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        original = destination.read_bytes()
        repeated = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(repeated.returncode, 1)
        self.assertEqual(destination.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
