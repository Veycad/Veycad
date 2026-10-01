"""Explicit, pre-seal product scope; a paused product is never release ready.

Only the user's Sigma pause is supported. This records scope, not a cryptographic
proof of user consent, planned case immutability or human montage acceptance.
"""

from __future__ import annotations

PRODUCTS = ("SIGMA", "HEARTBEAT", "FEAR_STROBE", "DUALITY_LOOP")
SIGMA_PAUSE_INSTRUCTION = (
    'Над Sigma пока приостанавливаем работу, в приложении его нужно '
    '"заблокировать" чтобы пользователи не могли воспользоваться(под замочек внести к примеру)'
)


def all_active_scope() -> dict:
    return {"schema_version": 1,
            "products": {recipe: {"state": "active"} for recipe in PRODUCTS}}


def sigma_paused_scope() -> dict:
    scope = all_active_scope()
    scope["products"]["SIGMA"] = {
        "state": "paused", "reason_code": "user_requested_pause",
        "instruction": SIGMA_PAUSE_INSTRUCTION,
    }
    return scope


def validate_scope(scope: object) -> list[str]:
    if not isinstance(scope, dict) or set(scope) != {"schema_version", "products"} or \
            type(scope.get("schema_version")) is not int or scope["schema_version"] != 1:
        return ["product scope: invalid schema"]
    products = scope.get("products")
    if not isinstance(products, dict) or set(products) != set(PRODUCTS):
        return ["product scope: all four exact product keys required"]
    errors = []
    for recipe in PRODUCTS:
        record = products[recipe]
        allowed = [{"state": "active"}]
        if recipe == "SIGMA":
            allowed.append(sigma_paused_scope()["products"]["SIGMA"])
        if record not in allowed:
            errors.append(f"product scope: invalid {recipe} state or pause basis")
    return errors


def manifest_scope(manifest: dict) -> tuple[dict, list[str]]:
    if "product_scope" not in manifest:
        # Never reinterpret a historical series using today's pause.
        return all_active_scope(), []
    errors = validate_scope(manifest["product_scope"])
    # Invalid scope cannot remove any requirements.
    return (all_active_scope() if errors else manifest["product_scope"]), errors
