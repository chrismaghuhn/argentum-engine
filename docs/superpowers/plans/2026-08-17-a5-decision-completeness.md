# A5 Decision Completeness Implementation Plan

## Baseline and audit

1. Keep branch `agent/a5-decision-completeness` rooted directly at the pinned
   baseline and independent of A3.
2. Inventory all current `PendingDecision`/`DecisionResponse` pairs, validators,
   continuation registrations, and hidden-choice call sites.
3. Implement the bounded delayed attack-player occurrence choice when a generic,
   serializable result/continuation seam is safe; preserve fail-closed behavior
   for any deeper trigger-ordering gap rather than choosing by iteration order.

## TDD validator slice

1. Add failing tests for duplicate `SelectCards` IDs, missing required target
   requirements, duplicate modes where the decision does not advertise repetition,
   negative distribution values, and invalid manual mana-source IDs.
2. Implement only the generic validator checks needed by those tests. Keep all
   checks tied to the decision's advertised domain; never infer legality from
   iteration order or a policy heuristic.
3. Run existing decision, combat, payment, Commander, and delayed-trigger tests.

## Final audit and handoff

1. Classify decision families as complete, partial, disabled/versioned, or blocked;
   list hidden-policy findings separately from engine-random effects and record
   the bounded Issue #22 occurrence-choice result.
2. Run `git diff --check` and all available `just` gates; distinguish launcher
   failures from code failures.
3. Commit, push, and open one draft PR against
   `chrismaghuhn/argentum-engine:main`, without merging or touching A3/A8.
