# Android V2 frozen KEEP fixtures

These synthetic fixtures were vendored from the repository-root
`tests/fixtures/` when V2 Phase A started on 2026-08-04. They are now owned by
the Kotlin implementation and must not be refreshed automatically from the
Python CLI. A deliberate Kotlin contract change updates these files and the
corresponding KEEP tests together.

Real `runs/2026-08-03/` material is not vendored. It remains local-only and is
copied into the test runtime solely for explicitly labelled MIGRATION-GUARD
tests until Phase C retires those guards one by one.

The truth-source decision is intentional: Kotlin KEEP tests are the Android
oracle from Phase A onward; Python remains a CLI reference implementation.

## Frozen SHA-256

- `all_empty_but_ok.json`: `20ce79835e71fa69d4445d949542c6526d1787058d2a85e9fa3c01fdda2fa635`
- `all_error.json`: `286a16bf801cbf7ecd434781a48bd94e1fc84bd60c038c14e3af8c13d3457e3e`
- `article_samples.json`: `55ebbe24e4f00d21f6c45bbffda7648bbaf22a7bed24f1a1ccc22a8e0c700a87`
- `dedup_collision.json`: `dfdf7b9e172e070b4c636ce6d173b5a73f474d3b77c7eff2ddd1af5d1a017018`
- `feeds_fixture.json`: `f919f787d82791adcae9bb3cb711be173f5a63deca69e0625a13c85d85eb1efe`
- `golden_success.json`: `a3ac67d060351ec2d4bda6e23979d10c8a4e71259fee59e36757dc22d48b23b5`
- `llm_context_golden.json`: `6dba5835fa6d9208e1f36e70291bbf579905eca721c7fc94b879bd36bab328da`
- `markdown_render_golden.md`: `9e7b4f41850558554e3a9b18e6e60a92ebe325748229bc4afb21974cc679f25f`
- `partial_failure_mix.json`: `0b9341e42e2ff4b3dbbfa4b16fb24bcd92e871ba49ea93ce50060f3d45af6ebc`
- `pipeline_config_fixture.json`: `1041a605eb79d4d426ccbc82653236c0a9b046b568ac3c64c2d20cdc2c965d5b`
