# Watching headless Gym games in the web replay viewer

Tournaments between model checkpoints run headless through `com.wingedsheep.gym.GameEnvironment`
for speed. To show a game in a devlog entry, record it as a replay and play it back in the normal
web replay viewer (`/replay/<gameId>`). Nothing in the viewer or in replay serving is special for
these games: they are ordinary `CompactReplay` v6 records, re-simulated and spectator-masked by the
same `ReplayService` path every live game uses.

## Pieces

| Piece | Where | What it does |
|---|---|---|
| `GameEnvironment.committedTransitionListener` | `gym/.../GameEnvironment.kt` | Reports every rules transition a step commits. A LEGACY `step()` commits several: the submitted action, then the automatic priority passes and forced decision answers of the quiet-state loop (`GameSimulator.simulate`). All of them are replay inputs. |
| `HeadlessReplayRecorder` | `game-server/.../replay/HeadlessReplayRecorder.kt` | Resets an environment with a `GameConfig` (fixed seed, explicit seat ids), listens to its transitions, stamps checkpoints at the live cadence (every 20 actions, plus action 0 and the tail), and `finish()` builds the `CompactReplay` with pinned cards. Works for any seat — engine AI, model, script. |
| `HeadlessShowcaseReplays` (opt-in test) | `game-server/src/test/.../replay/` | Plays engine AI vs engine AI on Akiri vs Chevill (`docs/ml/curriculum/*-v0.1.txt`), records each game, verifies it re-simulates **EXACT**, writes `<gameId>.replay` files and a markdown index. |
| `POST /api/dev/replays/import` | `game-server/.../controller/DevReplayImportController.kt` | Dev-only (`game.dev-endpoints.enabled=true`). Stores a replay file in the server's replay store so `/replay/<gameId>` can play it. Refuses to overwrite a different replay under the same id. |

## Recording from your own loop

```kotlin
val environment = GameEnvironment.create(registry)          // default LEGACY mode, engine auto-pay
val recorder = HeadlessReplayRecorder.start(environment, registry, config, maxSteps = 3_000)
while (!environment.isTerminal && !environment.isTruncated) {
    environment.step(chooseAction(environment))             // any policy, any seat
}
val replay = recorder.finish(gameId = "devlog-ckpt-0042-game-1", engineVersion = gitSha)
Files.writeString(Path.of("$gameId.replay"), ReplayCodec.encode(replay))
```

Requirements: `config.seed` set, every `PlayerConfig.playerId` set, and `registry` the one the
environment was created with. Do not `reset`/`restore` the environment while recording — `finish()`
detects it and refuses. The recorder lives in `:game-server` because the replay format does;
`:gym` stays free of that dependency (its tests already depend on `:game-server`).

To check a replay the way the viewer will, run `ReplayReconstructor(registry, null).reconstruct(replay)`
and expect `fidelity == EXACT`.

These are **viewer-grade** replays, not trusted training data. The Gym trajectory verification
(`GymReplayFrameSource`, the A9/TRUSTED gate) rejects them on purpose: a LEGACY game pays costs with
engine auto-pay, and the trusted gate only admits explicit `PaymentPlanV3` payments. Record training
data through the TRUSTED path; record showcase games with this one.

## Showcase games end to end

1. Record games (each takes ~1–3 minutes):

   ```bash
   just showcase-replays 2
   ```

   Files land in `build/showcase-replays/` together with `showcase-<timestamp>.md`, a table of
   seed, winner, turns, and fidelity per game. Options: `just showcase-replays GAMES SEED OUTPUT`.

2. Start the server with dev endpoints enabled (`.env.example` has `GAME_DEV_ENDPOINTS_ENABLED=true`)
   and the web client:

   ```bash
   just dev
   ```

3. Import a file and open the path it prints:

   ```bash
   just replay-import build/showcase-replays/<file>.replay
   ```

   The response looks like `{"gameId":"showcase-…","viewerPath":"/replay/showcase-…","fidelity":"EXACT",…}`.
   Open `http://localhost:5173` + `viewerPath`.

The dev server's default replay store is in memory, so imports are gone after a restart — just
import again. Imported replays are served by the same public replay endpoint as any finished game
and show the spectator view (hidden hands stay hidden); this changes nothing about live spectator
admission (`arena-01a-spectator-privacy-admission-2026-09-15.md`). The endpoint does not exist
unless dev endpoints are enabled, which must never be the case in production.

## Why the LEGACY log is longer than the number of AI moves

`GameEnvironment.step()` in LEGACY mode runs the AI simulator's quiet-state loop after the submitted
action: while the stack is non-empty it passes priority for whoever holds it, and it answers
decisions with exactly one legal choice. The live server records every action it applies; the
recorder does the same, so a replay usually has several times more actions than the AIs submitted.
This also means that in LEGACY Gym games neither player responds to a spell on the stack — the
replay shows what the environment actually did.
