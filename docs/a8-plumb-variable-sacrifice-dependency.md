# Plumb the Forbidden — A8 dependency boundary

This branch implements the independent variable-sacrifice publication slice for issue #49:
the engine's eligible candidate set, submitted-list cardinality, and structured Gym observation
contract are explicit and deterministic.

The resolving-copy/reflexive-trigger integration is intentionally not duplicated here:

`BLOCKED_BY_ACTIVE_PR_52`

PR #52 owns the resolving spell-copy payload. PR #38 owns trigger ordering/reflexive-trigger
integration. The #49 implementation must be reviewed and merged with those ownership boundaries
intact; this branch is not READY_TO_MERGE for the dependent copy behavior.
