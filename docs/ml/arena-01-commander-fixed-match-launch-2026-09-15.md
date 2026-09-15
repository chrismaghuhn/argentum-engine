# ARENA_01 — Commander fixed-match launch seam

Date: 2026-09-15
Base: `8cf794c4d1b111f60ff8157ffa70c6ef67df6618`
Branch: `chris/arena-01-commander-fixed-match-launch-20260915`

## Executive conclusion

ARENA_01 now launches the locked Akiri-versus-Chevill curriculum matchup through the existing
server-owned tournament and Commander runtime. The dev endpoint accepts the stable preset identity
`argentum-mtg-ml-akiri-chevill-curriculum@v1` (and the narrow request alias
`MTG_ML_AKIRI_CHEVILL_COMMANDER`), while the server owns the source-path mapping.

The legacy `decks: List<Map<String, Int>>` request remains unchanged and continues to create the
existing Standard/PREMADE_DECKS path. The new preset is a separate structured branch: it loads both
repository files byte-for-byte, recomputes their SHA-256 digests, derives the commander and merged
100-card map, validates a structured `Deck` against `DeckFormat.COMMANDER`, and only then creates the
AI tournament lobby.

No Commander rules, ML, UI, replay semantics, training, RL, or self-play code was added. The launched
match is public-spectatable through the existing `SpectatorAdmissionPolicy` because this exact preset
is intentionally public; there is no global development bypass.

## Reused architecture

The implementation is orchestration around existing modules:

```text
server-owned preset identity
        ↓
CurriculumDeckSourceLoader (raw bytes + strict TSV validation + SHA-256)
        ↓
DeckValidator.validate(Deck(cards = 99, commander = explicit), COMMANDER)
        ↓
TournamentLobby(PREMADE_DECKS, rules = COMMANDER, deckFormat = COMMANDER)
        ↓
TournamentLobby.submitDeck(merged 100-card map, commander)
        ↓
TournamentMatchHandler.startSingleMatch
        ↓
GameSession.engineFormat = Format.Commander()
        ↓
GameSession.addPlayer(..., commanderCardName = commander)
        ↓
GameInitializer + existing AiGameManager/AiWebSocketSession lifecycle
```

The match still starts through the normal `TournamentManager` readiness path. The dev controller does
not call AI logic, execute actions in a loop, or construct a second `GameSession` implementation.

## Exact-SHA review follow-up

The first implementation review identified one P2: `engineAiOnly` was only consulted during later
controller wiring, while `createAiIdentity()` and recovery still required `isEnabled`, which couples
the locked Engine-AI preset to LLM-key availability under global LLM mode.

The follow-up keeps normal AI semantics unchanged and carries one explicit `forceEngine` marker through
the existing identity and persistence surfaces. A force-engine create/rehydrate/wire operation requires
only `aiEnabledToggle`, creates an Engine-AI placeholder, suppresses misleading LLM model suffixes,
and selects `EngineAiPlayerController`. Normal seats continue to use `isEnabled`; recovery applies the
same per-seat distinction and still honors the master toggle.

## Curriculum source identities

The authoritative files were not copied or edited:

| seat | repository-relative source | SHA-256 | rows | commander |
|---|---|---|---:|---|
| A | `docs/ml/curriculum/akiri-v0.1.txt` | `E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04` | 100 | Akiri, Fearless Voyager |
| B | `docs/ml/curriculum/chevill-v0.1.txt` | `0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474` | 100 | Chevill, Bane of Monsters |

`.gitattributes` pins these two authoritative TXT artifacts to `eol=crlf`. The file contents and card
rows are unchanged; the policy makes the raw-byte digest stable across Windows and Linux checkouts,
which is required for source identity.

`CurriculumDeckSourceLoader` hashes the exact bytes read from disk before parsing. It accepts only
repository-relative paths below its configured repository root; the endpoint never accepts a caller
path or caller digest.

The parser ignores blank lines and `#` comment/header lines, requires the actual four-column
tab-separated row shape, requires slots `001` through `100` in order, requires one `# Commander:`
header and one `COMMANDER` row, and requires the header and row commander names to agree. Missing
files, invalid UTF-8, malformed rows, blank fields, duplicate/non-contiguous slots, missing or
multiple commander rows, wrong row count, and disagreement between header and row fail closed.

## Deck derivation and validation

The source row map is the only canonical deck source. The loader derives a transient merged map by
counting the 100 rows. `CurriculumDeckSourceV1.libraryDeckList()` removes exactly one occurrence of
the designated commander for the engine-facing 99-card library, and `asCommanderDeck()` builds the
structured `Deck` expected by the existing validator.

Before any lobby or AI identity is created, both structured decks pass:

```text
DeckValidator.validate(structuredDeck, DeckFormat.COMMANDER)
```

That existing validator performs card resolution, exact 100-card size, singleton/copy limits,
commander eligibility, color identity, and explicit format legality checks. The lobby submission keeps
the source's 100-card map plus the explicit commander because that is the existing lobby convention;
`TournamentMatchHandler` removes one commander copy only at the engine boundary.

The existing `AiDeckSpec.Fixed` view receives only the derived transient 99-card map for display
summary purposes. It is not written as a second persisted canonical deck source; provenance stores
only source path, digest, commander, and row count.

## Lobby and GameSession configuration

The locked preset constructs a two-seat `TournamentLobby` with:

```text
format                 = PREMADE_DECKS
rules                  = COMMANDER
deckFormat             = COMMANDER
deckSizeMin            = 100
allowDuplicates        = false
gamesPerMatch          = 1
isPublic               = true
immutableFixedDeckSource = true
engineAiOnly           = true
```

The public flag is scoped to this exact server-owned curriculum preset. It makes the intended bot
match watchable through the merged admission gate; it does not make every dev game public and it does
not make a game-session ID a bearer capability.

`TournamentMatchHandler.startSingleMatch` remains authoritative. Because this is
`PREMADE_DECKS` with `rules = COMMANDER`, it selects `Format.Commander()` rather than a Brawl or
limited preset. It strips one commander copy from each submitted source map, calls the existing
`GameSession.addPlayer` with `commanderCardName`, links the game to its lobby, and calls the normal
`GamePlayHandler.startGame`. `GameInitializer` then creates the command-zone entity and commander
registry from the existing engine path.

The runtime behavioral test observes the initialized raw `GameState` and proves for both seats:

- `state.format == Format.Commander()`;
- life total is 40;
- the command zone has exactly one card with the expected commander name;
- the commander carries `CommanderComponent` and the player has the matching
  `CommanderRegistryComponent`;
- the starting library contains the exact source semantic counts after removing the commander;
- both player persistence records are AI and both IDs are tracked by `AiGameManager`.

Opening-hand draw is normal GameInitializer behavior, so the source-count assertion uses the frozen
starting deck list and the command-zone assertion uses initialized state rather than treating the
post-draw library size as a 100-card value.

## Easter-egg and printing behavior

`immutableFixedDeckSource` is a generic lobby property. The tournament match path passes the existing
`game.easter-eggs.enabled` switch only when that property is false. Therefore the locked curriculum
preset cannot receive an EasterEggDeckInjector mutation, while unrelated tournament lobbies retain
their previous behavior.

`BoosterGenerator.withBasicLandArt` and `withCardArt` remain in the existing match path. They may bind
printing/art suffixes, but they do not change semantic card names or counts. The runtime test compares
names after removing an optional printing suffix and confirms that no `Sekshaas, Early Sleeper` card was
added.

## AI controller path

The preset creates marked AI identities through `AiGameManager.createAiIdentity(forceEngine = true)`.
That path requires only the existing `aiEnabledToggle` master switch, creates an Engine-AI placeholder,
and omits any global LLM model suffix. Normal identities still use the existing `isEnabled` gate.

At match start, `TournamentMatchHandler` calls `AiGameManager.wireAiForGame` for each seat with the
identity/lobby force marker. The existing `AiWebSocketSession` receives the normal `GameStarted`,
mulligan, full/delta state, legal-action, and pending-decision messages; actions return through the
existing `GamePlayHandler` callbacks into `GameSession.executeAction`.

The narrow `forceEngine` argument is used only for the locked preset, so a global LLM configuration or
an accidental model override cannot silently turn this exact V1 matchup into an LLM seat. Recovery
applies the same distinction: the master toggle is required globally, normal AI seats retain the
LLM-key gate, and marked Engine-only identities are rehydrated and rewired without a key. The marker is
persisted in both lobby and game player metadata. No ML Policy controller exists here; Engine AI remains
a visual/debug opponent rather than a trustworthy scientific evaluation baseline.

## Spectator and status path

The preset stores a `CurriculumMatchProvenanceV1` on its lobby. The POST response and existing status
endpoint report the preset identity plus the two repository-relative paths, recomputed digests,
commanders, and row counts. No deck map, absolute path, hostname, PID, or raw `GameState` is added to
the wire.

The game is linked with the existing `GameRepository.linkToLobby`. A normal authenticated spectator
is admitted because this exact lobby is intentionally public; `SpectatorAdmissionPolicy` remains the
only admission policy. No `devEndpointsEnabled` shortcut, session-ID bypass, frontend authorization,
or spectator-protocol change was introduced. ARENA_01A's private-game rejection, restore re-check, and
server-side hidden-information projection remain covered by the surrounding regression tests.

## Persistence

The lobby persistence representation now retains the locked-source marker, Engine-AI-only marker,
source provenance, each player's designated commander, and each marked identity's `forceEngine` bit.
Game-session persistence retains the same AI-controller bit. This prevents a Redis restore from losing
the commander, re-enabling the EasterEgg mutation, or requiring an unrelated LLM key for an in-flight
locked match.

The pre-existing persistence omission for general `deckFormat`, `deckSizeMin`, and
`allowDuplicates` remains outside this slice. The locked match has already been structurally
validated before submission, and its authoritative restored Rules axis remains `COMMANDER`.

## Test evidence

Initial RED characterization was observed before the seam existed: posting the explicit curriculum
preset was handled by the legacy endpoint behavior and did not return the requested preset identity.

Focused native Gradle tests cover:

- exact source loading and both accepted SHA-256 values;
- strict malformed, blank-name, unsafe-path, duplicate/non-contiguous-slot, wrong-count, missing,
  multiple, and disagreeing commander declarations;
- server-owned preset identity resolution;
- legacy fixed AI maps remaining Standard;
- locked preset override rejection;
- exact lobby configuration and source provenance;
- actual initialized Commander state, 40 life, command zones, commander registry, AI seats,
  semantic starting-deck counts, EasterEgg protection, lobby linkage, and spectator admission;
- provenance/commander/guard persistence round-trip.

The direct affected existing tests cover Quick Game Commander AI, generated Commander legality,
Commander lobby rules/persistence, ARENA_01A admission and source masking, and GameSession spectator
lifecycle. The `just` wrapper remains an environment failure on this Windows host (`WinError 193`)
before Gradle starts; native Gradle results are reported separately. A dedicated integration test also
runs the locked preset with `game.ai.mode=llm`, an empty API key, and the master toggle enabled; it
proves that the match starts and both Engine AI identities rehydrate without LLM dependency.

## Scope classification and remaining work

```text
CORE_RULE_ENGINE_GAPS=0
GAME_SERVER_ORCHESTRATION_GAPS=0
SPECTATOR_ADMISSION_DEPENDENCIES=0
P1=0
P2=0
P3=1 (pre-existing opaque-library-ID observation; deferred)
```

The next separately reviewed work may use this seam for the development UI. ARENA_02 UI, ARENA_03
pacing, ML Policy gameplay, C1_06, training, RL, self-play, and any scientific Engine-AI evaluation
remain unauthorized.

## Non-goals

- no Research Arena UI or React/routing/CSS changes;
- no new Commander rules or GameSession implementation;
- no replacement of `SpectatorGameBoard` or spectator protocol;
- no arbitrary filesystem or deck-map endpoint input;
- no decklist edits, copies, normalization, card substitution, or silent source repair;
- no ML model, Gym/observation, Teacher, checkpoint, training, RL, or self-play work;
- no pacing control or action scheduling change;
- no PR creation in this slice.

## Implementation result

```text
PRESET_IDENTITY=argentum-mtg-ml-akiri-chevill-curriculum@v1
COMMANDER_STRUCTURED_VALIDATION=PASS
COMMANDER_RULES_AXIS=COMMANDER
DECK_FORMAT=COMMANDER
TOURNAMENT_FORMAT=PREMADE_DECKS
AKIRI_STARTING_LIFE=40
CHEVILL_STARTING_LIFE=40
AKIRI_COMMAND_ZONE_COUNT=1
CHEVILL_COMMAND_ZONE_COUNT=1
AKIRI_SOURCE_SEMANTIC_COUNTS_PRESERVED=YES
CHEVILL_SOURCE_SEMANTIC_COUNTS_PRESERVED=YES
EASTER_EGG_MUTATION_APPLIED=NO
AI_SEAT_COUNT=2
AI_CONTROLLER_PATH=EXISTING_AI_GAME_MANAGER
ENGINE_ONLY_WITHOUT_LLM_KEY=PASS
ENGINE_ONLY_RECOVERY=PASS
NEW_AI_LOOP_CREATED=NO
SPECTATOR_POLICY_BYPASSED=NO
ARENA_MATCH_SPECTATE_STATUS=AUTHORIZED_PATH_READY
```
