# Targetless `LegalAction` Cardinality Design

**Status:** Approved by the user on 2026-08-24

## Goal

Make a genuinely targetless `LegalAction` publish the canonical raw target cardinality:

- `targetRequirements = emptyList()`
- `minTargets = 0`
- `targetCount = 0` (the legacy flat maximum-target projection)

## Ownership and boundary

The defect belongs to the `LegalAction` constructor defaults. Its canonical ordered target domain
already defaults to an empty list, while the compatibility maximum defaults to one and carries that
value into the minimum default. The fix changes only the generic `targetCount` default from `1` to
`0`; `minTargets = targetCount` remains the derived default.

Target-bearing enumerators remain authoritative because they pass their resolved `targetCount` and
`minTargets` explicitly. This change does not alter payment, target-domain mapping, decklists,
card definitions, or card-specific production behavior.

## Rejected approaches

1. Adding zero-valued fields at every targetless emission site would duplicate the contract and leave
   other direct `LegalAction` construction sites vulnerable.
2. Normalizing the fields in Gym or DTO mapping would mask an invalid Rules boundary and leave raw
   engine consumers inconsistent.

## Regression matrix

- A raw targetless cast, including the locked-deck Bonesplitter cast, is `0/0` with no requirements.
- A normal single-target cast remains `1/1`.
- Bite Down retains two ordered `1/1` requirements.
- An optional single target remains `0/1`.

The regressions inspect the Rules-owned `LegalAction` first. Existing Gym action-domain tests remain
the surrounding public-contract guard.

## Verification boundary

Run the focused Rules tests, the locked action-target-domain test, the surrounding Rules/Gym suites,
and the repository's applicable `just` gates. Hosted CI on the Draft PR is required. Seed-0 and the
72-episode corpus are explicitly out of scope until this production PR is independently reviewed and
merged.
