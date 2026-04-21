"""Shared utilities for the DailyNews pipeline scripts.

Modules in this package must be import-safe with no side effects, so that
unit tests can load them in isolation. Behaviour parity with the original
inlined implementations is enforced by `tests/test_contracts_snapshot.py`
and the existing offline suite.
"""
