# A3 Commander Gym Implementation Plan

## Baseline and isolation

1. Keep branch `agent/a3-commander-gym` rooted at pinned `c78db93c01f22b64d08725ee7a605cd0c9364f8d`.
2. Do not touch A8 worktrees, card definitions, locked decklists, or the root
   worktree's unrelated `.gitignore` change.
3. Record the locked Akiri/Chevill curriculum hashes and closure status without
   editing either curriculum file.

## Contract-first implementation

1. Add failing focused tests for `EnvConfig` Commander mapping, explicit seed
   reproducibility, two-player enforcement, and commander identity propagation.
2. Add failing tests for `maxSteps`: active before the limit, truncated exactly at
   the limit, no legal actions or action execution after truncation, and natural
   terminal state precedence.
3. Add failing tests for fork/snapshot horizon preservation and actor-bound
   structured decision submission.
4. Extend Gym configuration mapping and lifecycle state with the minimum fields
   in the design spec. Preserve defaults for all existing Standard callers.
5. Add the truncation bit to the shared observation contract, bump the schema hash,
   and ensure the state digest includes it through the existing canonicalizer.
6. Thread horizon state through fork, snapshot, restore, and reset; do not create a
   second serializer.
7. Add the optional HTTP actor claim and fail closed on a mismatch. Keep the
   existing structured `DecisionResponse` serialization and error handling.

## Regression and handoff

1. Run `git diff --check` and focused tests through `just`.
2. Run the required rules/Gym/Gym-server regression gates and classify launcher or
   unrelated failures separately.
3. Audit the real HTTP payloads for masking and absence of reveal-all behavior.
4. Commit coherent changes on this branch, push it, and open one draft PR against
   `chrismaghuhn/argentum-engine:main` only. Never merge it.
