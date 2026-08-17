# A3 Commander Gym audit

This is the implementation/audit record for `agent/a3-commander-gym`. The
rules engine remains authoritative; this branch only carries Gym configuration,
lifecycle, transport, and observation-contract state.

## Pinned provenance

| Item | Evidence/status |
| --- | --- |
| `BASE_SHA` | `c78db93c01f22b64d08725ee7a605cd0c9364f8d` |
| Origin main at audit time | `c78db93c01f22b64d08725ee7a605cd0c9364f8d` |
| Pinned baseline ancestor of origin main | Yes |
| Akiri curriculum source commit | `31853cfe91b52718dc3fb67f159e6267d9c5fcc1` |
| Chevill curriculum source commit | `31853cfe91b52718dc3fb67f159e6267d9c5fcc1` |
| Locked upstream provenance | `d66a5d7f1b46b0ed8891c34ccfe163d491c4ff3d` |
| Akiri curriculum SHA-256 | `E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04` |
| Chevill curriculum SHA-256 | `0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474` |

The curriculum files were not changed. The exact Akiri/Chevill pair is
representable by the normal `EnvConfig`/`PlayerSpec`/`DeckSpec` contract, but
the exact boot is not claimed here:

`EXACT_PAIR_BOOT = BLOCKED_BY_A8_CARD_CLOSURE`

No substitute commander or card definition was added.

## Contract matrix

| Contract | Current implementation | Focused coverage | Trust status | A3 gap | A4 dependency | A5/A6 dependency |
| --- | --- | --- | --- | --- | --- | --- |
| Commander reset | `EnvConfig` maps `Format.Commander`, seed, commander identity, and positive horizon through `MultiEnvService` to `GameConfig`/`GameInitializer`. | `CommanderGymContractTest` (5/5) | Implemented; focused runtime green through Git Bash `just` fallback | Exact locked pair still awaits A8 closure | None beyond existing public observation | Existing rules Commander tests |
| 1v1 validation | Commander configs require exactly two players and a non-blank commander identity for each player. | Configuration rejection test | Implemented | Direct non-Gym `GameConfig` remains the rules layer's responsibility | None | None |
| Command zone/life/tax/damage | Existing `GameInitializer` and Commander runtime own setup, tax, cast count, and commander damage. Gym does not duplicate state. | Existing rules coverage; controlled Gym fixture checks command-zone identity/life, cast/tax recast, zone replacement, commander damage, terminal/winner | Preserved; not reimplemented | Exact Akiri/Chevill boot remains blocked by A8 | Public fields remain an audit item if policy needs them | A5 owns external Commander-zone choices |
| Same-seed reset | Explicit `seed` is threaded into the authoritative initializer; observation semantic digest is compared for identical configs. | `CommanderGymContractTest` | Static implementation plus blocked focused run | Different-seed trajectory evidence remains unrun | Existing digest/privacy contract | Replay/fingerprint regression remains unrun |
| Step/action lifecycle | `GameEnvironment` rejects terminal/truncated steps, stale/non-owner actions, and illegal simulation results before mutating state or horizon; target templates are materialized and matched against current action semantics. | `GameEnvironmentTest`; full `:gym:test` (85/85) | Green locally through Git Bash `just` fallback | Exact locked pair still awaits A8 closure | Observation remains perspective-safe | A5 audits candidate completeness |
| Structured decision handoff | Optional HTTP actor claim is checked against the pending decision owner before `SubmitDecision`. Decision ID and rules validation remain authoritative. | `EnvControllerTest`; full `:gym-server:test` (13/13) | Green locally through Git Bash `just` fallback | Exact locked pair still awaits A8 closure | No raw state/reveal route added | A5 owns decision inventory |
| Horizon | `ACTIVE`, `TERMINAL`, and `TRUNCATED` are distinct. Horizon preserves Magic winner semantics and blocks post-horizon action. | `CommanderGymContractTest`; full `:gym:test` | Green locally through Git Bash `just` fallback | No external time-limit policy beyond `maxSteps` | `truncated` added to versioned observation | Snapshot/fork state is preserved |
| Fork/snapshot/restore | Existing immutable `GameState` path is reused; `stepCount` and `maxSteps` are carried in fork/snapshot entries. | `CommanderGymContractTest`; `EnvControllerTest`; full `:gym:test` and `:gym-server:test` | Green locally through Git Bash `just` fallback | In-process codec is not a durable wire serializer | Schema remains explicit | A6 replay parity gate remains separate |
| HTTP/privacy | Production controller has no `revealAll` argument or bypass. Stale README query claims were removed; observe still uses `ObservationBuilder`. | `EnvControllerTest` 13/13, including real create/observe/step/fork/snapshot/restore/reset/dispose and opponent-hand masking | Green locally through Git Bash `just` fallback | Exact locked pair still awaits A8 closure | Relies on accepted A4 builder | None |

## Observation decision

The only new wire field is `truncated`; the schema hash is bumped to
`argentum-gym-contract@v1.4-privacy-horizon`. Hidden zones continue to use the
existing `ObservationBuilder` masking path. Commander identity is already public
in the command zone. Commander tax/cast-count and the commander-damage matrix
remain authoritative engine state; this branch does not invent observation-only
tracking. If the locked Commander policy later needs those fields, that is a
focused A4 follow-up rather than a Gym-side duplicate.

## Verification and remaining classifications

`git diff --check` is clean. The native Windows `just test-class` launcher still
fails before Gradle because the Python 3.14 path executes the extensionless
helper with `WinError 193`. The equivalent repository `just` invocation with
`--command "C:\Program Files\Git\bin\bash.exe" scripts/gradle-locked` reached
the locked Gradle wrapper and produced these green results:

* `:gym:test`: 85/85
* `:gym-server:test`: 13/13
* `CommanderGymContractTest`: 5/5
* `ManaAbilityEnumeratorTest`: 8/8, including the costed-mana regression

The local runtime evidence is therefore **GREEN_WITH_DOCUMENTED_LAUNCHER_FALLBACK**.
`EXACT_PAIR_BOOT` remains **BLOCKED_BY_A8_CARD_CLOSURE**; no substitute cards or
definitions were used, so A3 remains **PARTIAL** at the overnight scope level
even though the generic Gym and HTTP contracts are green.
