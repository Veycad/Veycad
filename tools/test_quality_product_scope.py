import copy
import unittest

from quality_product_scope import (
    PRODUCTS, all_active_scope, manifest_scope, sigma_paused_scope, validate_scope,
)


class ProductScopeTest(unittest.TestCase):
    def test_supported_scope_retains_four_products(self):
        expected = {"SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"}
        self.assertEqual(expected, set(PRODUCTS))
        for scope in (all_active_scope(), sigma_paused_scope()):
            self.assertEqual(set(scope["products"]), expected)
            self.assertEqual(validate_scope(scope), [])

    def test_factories_return_fresh_nested_records_and_pause_only_sigma(self):
        active = all_active_scope()
        paused = sigma_paused_scope()
        self.assertEqual({recipe: {"state": "active"} for recipe in
                          ("SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP")}, active["products"])
        self.assertEqual("paused", paused["products"]["SIGMA"]["state"])
        self.assertEqual("user_requested_pause", paused["products"]["SIGMA"]["reason_code"])
        for recipe in ("HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP"):
            self.assertEqual({"state": "active"}, paused["products"][recipe])
        active["products"]["HEARTBEAT"]["state"] = "paused"
        paused["products"]["SIGMA"]["instruction"] = "changed"
        self.assertEqual({"state": "active"}, all_active_scope()["products"]["HEARTBEAT"])
        self.assertEqual([], validate_scope(sigma_paused_scope()))

    def test_legacy_omission_is_all_active_not_today_sigma_pause(self):
        scope, errors = manifest_scope({})
        self.assertEqual(scope, all_active_scope())
        self.assertEqual(errors, [])

    def test_invalid_types_or_schemas_cannot_remove_requirements(self):
        for value in (None, [], "paused", True, {},
                      {"schema_version": True, "products": all_active_scope()["products"]},
                      dict(sigma_paused_scope(), ignored_cases=["failed-fear"])):
            with self.subTest(value=value):
                scope, errors = manifest_scope({"product_scope": value})
                self.assertTrue(errors)
                self.assertEqual(scope, all_active_scope())

    def test_missing_unknown_products_or_arbitrary_active_pause_rejected(self):
        for recipe in PRODUCTS:
            for change in ("missing", "unknown", "paused"):
                with self.subTest(recipe=recipe, change=change):
                    scope = all_active_scope()
                    if change == "missing":
                        scope["products"].pop(recipe)
                    elif change == "unknown":
                        scope["products"]["OTHER"] = {"state": "active"}
                    else:
                        scope["products"][recipe] = {"state": "paused"}
                    self.assertTrue(validate_scope(scope))

    def test_sigma_pause_requires_exact_user_basis_not_freeform_exclusion(self):
        original = sigma_paused_scope()
        for key in ("reason_code", "instruction"):
            for mode in ("missing", "wrong"):
                with self.subTest(key=key, mode=mode):
                    scope = copy.deepcopy(original)
                    if mode == "missing":
                        scope["products"]["SIGMA"].pop(key)
                    else:
                        scope["products"]["SIGMA"][key] = "failed case ignored"
                    self.assertTrue(validate_scope(scope))
        scope = copy.deepcopy(original)
        scope["products"]["SIGMA"]["excluded_cases"] = ["failed-sigma"]
        self.assertTrue(validate_scope(scope))


if __name__ == "__main__":
    unittest.main()
