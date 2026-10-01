"""Validate human review attribution, not the truth of a claimed viewing.

A nonblank name and an aware, nonfuture ISO datetime are necessary evidence.
They are not a signature or a substitute for output binding and the checklist.
"""

from __future__ import annotations

from datetime import datetime, timezone


def valid_review_identity(review: object, *, now: datetime | None = None) -> bool:
    if not isinstance(review, dict):
        return False
    reviewer = review.get("reviewer")
    reviewed_at = review.get("reviewed_at")
    if not isinstance(reviewer, str) or not reviewer.strip() or \
            not isinstance(reviewed_at, str) or not reviewed_at.strip():
        return False
    try:
        timestamp = reviewed_at.strip()
        # Accept UTC Z on Python versions that do not yet parse it directly.
        if timestamp.endswith("Z"):
            timestamp = timestamp[:-1] + "+00:00"
        reviewed = datetime.fromisoformat(timestamp)
        current = datetime.now(timezone.utc) if now is None else now
        return reviewed.tzinfo is not None and reviewed.utcoffset() is not None and \
            current.tzinfo is not None and current.utcoffset() is not None and \
            reviewed <= current
    except (TypeError, ValueError, OverflowError):
        return False
