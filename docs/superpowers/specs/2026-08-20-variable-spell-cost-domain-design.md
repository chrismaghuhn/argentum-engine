# Variable spell-cost domain design

Date: 2026-08-20  
Issue: #67  
Base: `origin/main` at `b43b41d19f0d671e2b5a173a94bd133cf6e8c415`

## Goal

Publish the complete, explicit sacrifice domain for normal spell additional costs represented by `CostAtom.VariablePermanents`. The legal-action surface must describe the same domain that `CastSpellHandler` validates.

## Boundary

This change connects the existing `VariablePermanentsCost` and `SelectionCostPresentation` authorities to the normal hand-cast path in `CastSpellEnumerator`. It does not add card-specific logic, SDK vocabulary, a new Gym field, or a new payment validator.

## Root cause

The normal spell preflight handles fixed sacrifice, exile, discard, bounce, and tap atoms, but its `CostAtom.VariablePermanents` branch is absent. The preflight therefore leaves the cast payable without recording a selection contract. `buildAdditionalCostData` also has no variable-atom entry, so the resulting `LegalAction` has `additionalCostInfo == null`. The existing kicker and selection-helper paths already contain the intended candidate and presentation model.

## Design

During normal spell additional-cost preflight:

1. Flatten the additional costs as the current enumerator already does.
2. For a variable permanent sacrifice atom, call `SelectionCostPresentation.candidates(...)`. That call delegates to `VariablePermanentsCost.candidates(...)`, preserving controller, filter, battlefield projection, exclusion, and deterministic battlefield ordering.
3. Call `SelectionCostPresentation.costData(...)` with the resulting candidate list and retain the returned `AdditionalCostData` independently of candidate-list emptiness.
4. Gate payability with `VariablePermanentsCost.canPay(...)`. A zero minimum remains payable with zero candidates; a positive minimum rejects an insufficient domain.
5. Pass the retained selection data into `buildAdditionalCostData`, which returns it before the legacy non-empty-list branches.

The existing `ActionPayloadRequirements` behavior then supplies the Gym contract without a Gym production change: a non-null `additionalCostInfo` requires `additionalCostPayment`, including when the valid list is empty. `CastSpellHandler` remains the execution authority and continues to validate explicit IDs without silently selecting or correcting them.

## Contract cases

| Case | Legal-action result |
| --- | --- |
| `minCount = 0`, no candidates | Cast exists; `VariableSacrifice`; min/max `0`; empty candidate list; structured payment required |
| `minCount = 0`, `N` candidates | Cast exists; min `0`; max `N`; all legal candidates published |
| `minCount > candidates.size` | Cast is not enumerated by the normal payability convention |
| Nonmatching or opponent permanent | ID is absent from the published domain and rejected by the handler if submitted |
| Repeated enumeration of equivalent state | Same semantic candidate set and stable ordering |

## Test flow

The Rules regression uses a synthetic `CardDefinition` with a direct `additionalCosts` entry and covers zero, one, three, insufficient, filter/owner, and deterministic-order cases. The Gym regression drives an actual `GameGymEnv` observation, finds the enumerated `CastSpell`, submits explicit zero and valid IDs, and verifies missing and non-domain IDs fail before the environment advances.

Existing privacy, state-digest, snapshot, and replay tests remain the authority for unchanged masking and determinism layers. No frozen baseline is re-blessed.

## Non-scope

- No `PlumbTheForbidden` definition or scenario.
- No changes to PR #55, locked decks, Issue #49, or PR #66 behavior.
- No changes to `CastSpellHandler`, `ActionPayloadRequirements`, `ObservationBuilder`, or `AdditionalCostData` shape.
- No support claim beyond the reusable decision-surface prerequisite.



