# B1 GameGymEnv Cache Relevance Characterization

Status: Task 5 measurement/characterization only. No cache optimization was implemented.

```text
BASE=036205719a0ede84d7d9307a0bcca1c1b741fa4c
PRODUCTION_OPTIMIZATIONS=0
PRODUCTION_SEMANTIC_CHANGES=0
DIAGNOSTIC_PRODUCTION_HOOKS=NO
CACHE_PRODUCTION_FIX=NOT_AUTHORIZED
CACHE_RELEVANCE=NOT_PROVEN
TASK6=NOT_AUTHORIZED
DATA_TRUSTED=NO
```

## Caller inventory

The production call graph has one required opening observation path and one optional read path:

| Caller/path | Observation behavior | Cache relevance |
|---|---|---|
| MultiEnvService.create() | Calls GameGymEnv.observe() once after reset and returns that opening ObservationResult directly | Required opening observation; not an additional same-state read |
| MultiEnvService.step() | Calls GameGymEnv.step(), which commits and returns the new observation from the new state directly | No additional observe() call |
| MultiEnvService.submitDecision() | Calls GameGymEnv.submitDecision(), which commits and returns the new observation directly | No additional observe() call |
| MultiEnvService.reset() | Calls GameGymEnv.reset(), which returns the reset observation directly | No additional observe() call |
| MultiEnvService.observe() | Delegates to GameGymEnv.observe() | Potential cache miss on an explicit extra read |
| EnvController.observe() | HTTP GET endpoint delegates to MultiEnvService.observe() | Real external read seam exists; runtime traffic was not available |
| MultiEnvService.createDeckbuild() / DeckbuildEnvironment | Calls the deckbuild environment's observe() implementation | Not the GameGymEnv cache under investigation |
| gym-trainer SelfPlayLoop / AlphaZeroSearch | Uses GameEnvironment directly for step/restore/fork | Does not use GameGymEnv cache |
| web-client game code | No matching game-observation HTTP caller was found; observe() matches are DOM ResizeObserver/IntersectionObserver calls | No repository evidence of extra GameGymEnv reads |

The current GameGymEnv.build() still performs legal-action enumeration and ObservationBuilder.build()
before checking its existing step/perspective cache. That is a real correctness/performance candidate
for explicit repeated reads, but this characterization does not change it.

## B1 workload accounting

The existing B1PerformanceBaselineTest uses the returned values from create(), step(), and
submitDecision(). Its source contains no service.observe() call. Therefore the B1 workload protocol
has zero additional MultiEnvService.observe() caller invocations by construction, while still
exercising the normal strict observation path for every returned state.

```text
witness: 1 episode, 2,000 transitions, 0 additional service.observe() calls
normal4: 4 episodes, 8,000 transitions, 0 additional service.observe() calls
corpus8: 8 episodes, 16,000 transitions, 0 additional service.observe() calls
```

This is caller-protocol evidence, not a claim about unobserved external B0 traffic. The repository
contains B0HarnessTimeoutPolicy and its timeout tests, but no executable B0-64 runner or captured
trainer/HTTP request log that can establish repeated same-state reads.

## Result

```text
IN_REPO_B1_EXTRA_OBSERVE_CALLS=0
HTTP_OBSERVE_ENDPOINT=EXISTS
HTTP_OBSERVE_RUNTIME_TRAFFIC=NOT_RUN
B0_TRAINER_RUNTIME_CALLER=NOT_FOUND
CACHE_RELEVANCE=NOT_PROVEN
CACHE_FIX=NOT_AUTHORIZED
```

The ten-read local witness remains useful for proving the existing cache bug's behavior, but it is
not evidence that the bug contributes materially to corpus8 or B0 wall time. No production cache
fix, cache-key change, diagnostic production hook, state retention map, or observation change was
made.

## Commands executed and results

Fresh worktree baseline:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B0HarnessTimeoutPolicyTest" --console=plain
```

Result: `PASS`, 3/3 tests, 0 failures, `BUILD SUCCESSFUL`.

Caller search:

```powershell
rg -n --glob '!**/build/**' --glob '!**/.gradle/**' 'service\.observe|MultiEnvService.*observe|\.observe\(' gym/src/main gym-server/src/main gym-trainer/src/main
rg -n 'service\.create|service\.step|service\.submitDecision|service\.observe|created\.observation' gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
rg -n --glob '!**/build/**' 'observe\(|MultiEnvService|GameGymEnv' gym-trainer/src/main
```

Results: the only game-environment external read declaration is
gym-server/src/main/kotlin/com/wingedsheep/gym/server/controller/EnvController.kt:208-209;
the B1 profiler has create/step/submitDecision and no service.observe call; gym-trainer has direct
GameEnvironment step/restore usage and no GameGymEnv observation call. DOM observer matches in the
web client were excluded from game-call accounting.

No B0/trainer runtime caller or HTTP traffic measurement was available. Accordingly, this task
does not promote the cache to a production optimization target and does not run Task 6.
