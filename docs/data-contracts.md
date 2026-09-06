# Data Interfaces & Contracts

This document defines the JSON payloads exchanged between `web-client` and `game-server`.

The Gym observation contract is documented in the structured-decision section below as well;
it uses the same server-authoritative principle but is consumed by training clients rather than
the browser client.

## 1. Core Philosophy

* **Server -> Client:** The Server pushes the **Truth**. The Client renders it.
* **Client -> Server:** The Client pushes **Intent**. The Server validates it.

## 2. Gameplay Payload (WebSocket)

### A. State Update (Server -> Client)

Sent whenever the game state changes.

```json
{
  "type": "stateUpdate",
  // 1. The Visual State (Masked)
  "state": {
    "activePlayerId": "player-1",
    "phase": "MAIN_1",
    "zones": [
      {
        "name": "BATTLEFIELD",
        "cards": [
          {
            "id": "ent-1",
            "name": "Grizzly Bears",
            "tapped": true,
            "pt": "2/2"
          }
        ]
      },
      {
        "name": "HAND",
        "ownerId": "player-1",
        "cards": [
          {
            "id": "ent-2",
            "name": "Generous Gift"
          },
          // Visible to owner
          {
            "id": "ent-3",
            "name": "???"
          }
          // Masked to opponent
        ]
      }
    ]
  },
  // 2. The Animation Stream (What just happened?)
  "events": [
    {
      "type": "Tapped",
      "entityId": "ent-1"
    },
    {
      "type": "DamageDealt",
      "targetId": "player-2",
      "amount": 2
    }
  ],
  // 3. The Legal Actions (What can I do now?)
  // The ENGINE calculates this. The Client just renders it.
  "legalActions": [
    {
      "actionId": "act-1",
      "type": "PlayLand",
      "description": "Play Forest",
      "sourceId": "ent-5"
    },
    {
      "actionId": "act-2",
      "type": "CastSpell",
      "description": "Cast Shock",
      "sourceId": "ent-6",
      // If targeting is needed, the engine provides the Valid Candidates
      "targeting": {
        "required": true,
        "validTargets": [
          "ent-1",
          "player-2",
          "player-1"
        ]
      }
    },
    {
      "actionId": "act-3",
      "type": "PassPriority",
      "description": "Pass Turn"
    }
  ]
}
```

### B. Action Submission (Client -> Server)

Sent when the user interacts with the UI.

**Simple Action (No targets):**

```json
{
  "type": "submitAction",
  "action": {
    "type": "PlayLand",
    "cardId": "ent-5"
  }
}
```

**Complex Action (With targets):**

```json
{
  "type": "submitAction",
  "action": {
    "type": "CastSpell",
    "cardId": "ent-6",
    "targets": [
      {
        "id": "ent-1",
        "type": "Creature"
      }
    ]
  }
}
```

### B2. Persistent Yields (Client -> Server)

MTGO-style per-ability yields (backlog §C). Keyed by the ability's **AbilityIdentity**
(`cardDefinitionId` + `abilityId`), so a preference set once follows every current and future
copy/instance of that card ability. The server applies the change to the immutable `GameState`
(`yieldsByPlayer`), so it survives serialization and replays deterministically.

```json
{ "type": "setAbilityYield", "cardDefinitionId": "Soul Warden#ALA-25", "abilityId": "ability_42",
  "kind": "ALWAYS_ANSWER_YES" }
```

`kind` ∈ `YIELD_UNTIL_END_OF_TURN` (auto-pass priority on this ability's stack objects until end of
turn), `YIELD_WHOLE_GAME` (same, rest of game), `ALWAYS_ANSWER_YES` / `ALWAYS_ANSWER_NO`
(auto-resolve the ability's optional "you may" may-question). Revoke with
`{ "type": "clearAbilityYield", "cardDefinitionId": …, "abilityId": … }` or clear everything with
`{ "type": "clearAllYields" }`.

The viewer's own yields come back in the state update as `activeYields` (masked — a player never sees
another player's yields), each `{ cardDefinitionId, abilityId, displayName, untilEndOfTurn,
wholeGame, autoAnswer }`. A triggered/activated ability on the stack carries its `abilityIdentity`
in its `ClientCard`, so the stack-item context menu can target it. When a yield auto-answers a
may-question, the server emits an `abilityAutoAnswered` log event (shown only to the controller).

### B3. Deck Tracker (`deck` on the client state)

The state update carries the viewer's own decklist as `deck` — one entry per distinct card,
`{ cardName, copies, remaining, cmc, cardTypes, colors, imageUri }`. It drives the in-game deck
panel behind the Deck pile (also `D`), which renders it through the same `DeckCardBody` component
as the recorded-deck viewer — hence the field names matching `GameDeckCard`.

The server builds it in `ClientStateTransformer` from live *ownership* rather than a stored
decklist, which is what keeps it honest: a permanent an opponent stole is still in its owner's deck
(CR 108.3), a token copy of one never is, and a permanent copying something else counts as the card
it was printed as. The sideboard is excluded as outside the game (CR 400.11a); the command zone is
included so a commander doesn't flicker in and out as it's cast and returns.

Two masking rules matter:

- **`deck` describes only `viewingPlayerId`, and is empty for spectators.** No player, spectator or
  replay viewer ever receives another player's decklist.
- **`remaining` is "copies you can't currently see", not "copies in your library."** Those are the
  same number in an ordinary game, but a card of yours hidden elsewhere — exiled face down, or a
  face-down permanent an opponent controls — stays counted as `remaining`. Publishing an exact
  library count would let the panel be read backwards to learn *which* card got exiled face down.

Aggregate counts only: library *order* is never exposed here. (The Library-order tab in the same
panel is the pre-existing view, and shows card backs for everything not revealed to the viewer.)

`StateDelta.deck` is sent only when a count actually moved (a draw, a mill, a tutor), so the
many updates that just shuffle the battlefield around don't re-send the list. Absent from a delta
means unchanged — the client carries the previous value forward.

### C. Connection Liveness (Client <-> Server)

`{"type": "ping"}` (client) is always answered with `{"type": "pong"}` (server), regardless of
authentication or game state. The client sends it when a backgrounded tab becomes visible while
the socket still claims to be open: a socket can sit half-open after OS sleep without ever firing
`close`, and a silent server (no message within 5s) tells the client to tear the socket down and
reconnect. Any inbound message counts as proof of life, not just the pong.

Related recovery contracts:

- `{"type": "requestResync"}` (client) asks for a full `stateUpdate` instead of deltas — sent on
  tab return and when a `stateVersion` gap is detected.
- A `NOT_CONNECTED` error (server) means the socket is open but not associated with an
  authenticated session (e.g. the server restarted). The client recovers by re-sending `connect`
  with its stored token rather than surfacing the error.
- `{"type": "sessionReplaced"}` (server) is sent to the *previous* socket when the same identity
  (token) authenticates from a new socket — i.e. the player opened the game in another tab or
  device. The server closes that socket right after sending; the receiving client stops all
  auto-reconnect (reconnecting would steal the session straight back) and shows a takeover
  overlay whose "Use here" button reclaims the session explicitly.

---

## 3. Drafting Payload (REST / HTTP)

Drafting is lower frequency, so standard HTTP JSON is used.

**Request: Pick a Card**
`POST /api/draft/pick`

```json
{
  "draftSessionId": "sess_draft_0912",
  "packId": "pack_88",
  "cardId": "uuid-shivan-dragon"
}
```

**Response: Updated State**

```json
{
  "status": "PickRecorded",
  "waitingForOthers": true,
  // Or, if the next pack is ready:
  "nextPack": {
    "packId": "pack_89",
    "cards": [
      ...
    ]
  }
}

## 3a. Set Catalog & Coverage (REST / HTTP)

Set-level metadata for the deckbuilder, pickers, and the **Set Completion** view. Low frequency,
plain HTTP JSON.

**List sets** — `GET /api/sets` → `[{ "code", "name", "releaseDate" }]` (every catalogued set).
**Booster-ready** — `GET /api/sets/booster-ready` → `[{ "setCode", "setName", "implementedCount", "incomplete" }]`
(subset draftable for sealed/draft).

**Set coverage** — `GET /api/sets/coverage` → per-set card-implementation coverage, newest release
first. Powers the Set Completion grid (`/set-completion`). The headline `percent` is over the
**booster (draft)** cards only — a set reads 100% once every boosterable card is implemented; the
completionist extras are reported separately.

```json
[
  { "code": "BLB", "name": "Bloomburrow", "releaseDate": "2024-08-02", "setType": "expansion",
    "block": null, "implemented": 261, "total": 261, "extraImplemented": 18, "extraTotal": 18,
    "notPlanned": 0, "extraNotPlanned": 0, "percent": 100.0, "inStandard": true, "assayReady": 0 }
]
```

**Set detail** — `GET /api/sets/{code}/coverage` → one set's full canonical card list: the `draft`
pool plus the extras split into `extraGroups`, each card marked. 404 if the code isn't a catalogued
set with baked totals. Drives the click-through detail view.

```json
{ "code": "ELD", "name": "Throne of Eldraine", "releaseDate": "2019-10-04", "block": null,
  "implemented": 254, "total": 254, "extraImplemented": 0, "extraTotal": 31,
  "notPlanned": 0, "extraNotPlanned": 0, "percent": 100.0, "assayReady": 0,
  "draft": [{ "name": "Acclaimed Contender", "implemented": true,
              "imageUri": "https://cards.scryfall.io/normal/front/…jpg", "notPlanned": null,
              "assay": { "readsWhole": false, "kind": "LINE_DECLINED",
                         "line": "When Acclaimed Contender enters, if you control…" } }, ...],
  "extraGroups": [
    { "label": "Planeswalker Decks", "implemented": 0, "total": 10, "notPlanned": 0,
      "cards": [{ "name": "...", "implemented": false, "imageUri": "…", "notPlanned": null,
                  "assay": { "readsWhole": true, "kind": null, "line": null } }, ...] },
    { "label": "Brawl Decks", "implemented": 0, "total": 20, "notPlanned": 0, "cards": [...] },
    { "label": "Promos", "implemented": 0, "total": 1, "notPlanned": 0, "cards": [...] }] }
```

**Assay verdicts.** Every card carries `assay` — Argentum Assay's reading of it — and every set
carries `assayReady`, the count of booster cards *still to build* that Assay reads whole. That's the
free-to-implement number: such a card needs no new grammar and no new SDK vocabulary, so the view
badges it, filters to it, and can sort the whole grid by it. `null` means **unknown**, never "no":
either the ledger has no row for that card, or none is baked. The view distinguishes the two, since
"this set has no cheap work left" is a finding and "nobody baked the ledger" is a missing input.

Baked for the same reason the denominator is — production has no Scryfall cache, and computing a
verdict means running the grammar over Oracle text. `just assay-bake` writes
`game-server/.../resources/coverage/assay-verdicts.json`: one sorted line per card, `{ name }` when
[`CardCompiler`](../oracle-assay/README.md#the-verdict-ledger) reads it whole and
`{ name, kind, line }` when it doesn't, naming the decline and the printed line it points at.
`AssayVerdictService` joins it by front-face name; a missing or malformed resource degrades to no
badges rather than a server that won't boot. Re-bless it in its own commit after a grammar change —
the file doubles as Assay's per-card regression ledger, so the diff is the list of cards whose
reading moved.

**Assay Explorer** — `GET /api/assay/explorer` (+ `/{route}`) → the live Argentum Assay explorer,
mounted inside the app so the Set Completion view can offer it as a second tab. It is the *same*
page and the same handlers `just assay-explore` serves: `ExploreApi` in `:oracle-assay` owns every
route, both servers only move bytes, and the page's request prefix is substituted at serve time. The
client frames it in an `<iframe>` rather than reimplementing the views, which would be free to drift
from the gates they display.

Ungated: it is a read over public card text, holds no state a request can mutate, and touches no
game, account or corpus. The cost is resources, not exposure, and the sweep is lazy for exactly that
reason — `ExploreApi` is constructed and its corpus sweep started on the **first request**, so a
server nobody opens the tool on fetches nothing. Where `~/.cache/scryfall` is absent (a production
container) that first sweep downloads the 24 MB Oracle bulk; if it can't, the page stays up with its
live parser and rule tree and reports the failure through `status`. The differential decodes
`mtg-sets` *test* resources, which aren't in the bootJar, so in production that one page answers
"no goldens found" and the rest is unaffected.

**Extras are sectioned like a Scryfall set page.** scryfall.com/sets/`<code>` splits a set into
"Draft Cards" plus named runs of non-booster printings, and `extraGroups` mirrors the ones that
matter here — "Starter Decks", "Planeswalker Decks", "Brawl Decks", "Starter Collection",
"Beginner Box", "Set Extension", "Promos", "Special Art", and an "Other Cards" catch-all — so the
view can say *which product* a completionist card comes from. `scripts/gen-set-totals` derives each
label from the printings' Scryfall `promo_types` (see `EXTRA_GROUPS` there) and emits `extra`
pre-sorted into those sections; the server groups by label in encounter order. Scryfall's remaining
headings are art-variant runs (Borderless, Showcase, Extended Art, Raised Foil) — those are
alternate *printings* of cards already in the draft pool, so against this card-name denominator they
contain nothing new and never appear. Sectioning only partitions the extras: it never moves a card
in or out of `extraTotal` / `extraImplemented`. Sets with no booster at all have no extras and so no
sections.

**Cards we won't implement.** A card needing a mechanic the engine will never carry (ante, subgames,
physical dexterity) is listed in the repo-root `coverage/card-exclusions.json` manifest, keyed by name
so one entry covers every set that prints it. `scripts/gen-set-totals` bakes the flag onto the card as
`"notPlanned": { "kind": "ante", "why": "…" }` — exclusion is carried *as* its reason, so a not-planned
card can never render as an unexplained gap. Those cards stay in `draft` / `extraGroups` (the detail
view lists them with a badge) but drop out of `total` / `extraTotal` while unimplemented and are counted in
`notPlanned` / `extraNotPlanned` instead, so "complete" means *everything we intend to build is built*.
Implementing one silently un-excludes it: the flag only ever moves a card out of the still-to-do
bucket. `scripts/card-status` applies the same manifest in its `Skip` column.

**Implementation progress** — `GET /api/sets/progress` → the distinct-implemented-cards-over-time
series (one cumulative point per calendar day since the project began), `[{ date, added, total }]`.
Drives the chart behind the Set Completion overall-progress element. Git history isn't reachable at
runtime, so `scripts/card-progress-graph` bakes the series (alongside the root
`card-implementation-progress.html` + README SVG) into
`game-server/.../resources/coverage/implementation-history.json`.

The denominator (canonical booster + extra front-face card names) isn't knowable at runtime — it
lives only in the local Scryfall cache. `scripts/gen-set-totals` bakes those canonical cards, split
into `draft` (some printing of the card in that set is Scryfall `booster: true`) and `extra`, each
`{ name, img }` (direct CDN art URL) plus `{ products, group }` on the extras, into
the committed `game-server/.../resources/coverage/set-totals.json` resource (same partitioning as
`scripts/card-status`, so the numbers match the mtgish coverage TUI). Baking the art URL lets the
detail view render set-specific images for *missing* cards too, without hammering the rate-limited
Scryfall name-lookup API. At request time `SetCoverageService` joins that static denominator with the
*live* card catalog: `implemented` is the count of a set's canonical names we've actually authored
(`card` + `basicLand` + reprint `Printing` rows, front-faces) — an intersection, so it can never
exceed the canonical count. A set with no booster (Commander / supplemental, every card
`booster: false`) uses the whole set as the main pool, so its headline isn't a useless 0/0. Re-run
`scripts/gen-set-totals` (after `scripts/card-status --refresh`) to refresh totals for new/spoiler
sets.

## 3b. AI Assistance Payload (REST / HTTP)

In-app AI help for the player at the wheel: **Suggest Pick** (draft) and **Auto-build** (deckbuild).
Stateless w.r.t. the draft/deckbuild flow — the client sends card **names** (it already holds the
pack/pool) and the server re-resolves them against the card registry. The actual engines live behind
a pluggable SPI in the `ai` module (`AdvisorCatalog`). Two engines ship: **`heuristic`** (the
default, effect-tree heuristic) and **`draftsim`** (a port of the Draftsim ratings/archetype model;
loads per-set ratings/removal/archetype tables, falling back to a rarity ladder for sets it has no
table for). The client picks the engine via the per-player dropdown; `advisorId` omitted ⇒ default.

**Gating.** When a `lobbyId` is supplied and that tournament has `aiAssistEnabled = false` (a
`LobbySettings` field, host-toggled), every endpoint below returns **403**. The client also hides the
controls. Requests with no `lobbyId` (practice) are allowed. This gate is **advisory, not
anti-cheat**: it trusts the client-supplied `lobbyId` (as do the other REST endpoints), so a modified
client could still reach the engines. The toggle signals that assistance is unwelcome for an event;
it does not hard-enforce it.

**List engines** — `GET /api/ai-advisors` → `{ "draft": [{ "id", "name" }], "deckbuild": [...] }`.
Populates the per-player engine dropdowns.

**Suggest a pick** — `POST /api/draft/suggest-pick`

```json
{ "lobbyId": "lob_1", "advisorId": "draftsim", "pack": ["Shivan Dragon", "..."],
  "pickedSoFar": ["..."], "packNumber": 1, "pickNumber": 3, "picksRequired": 1,
  "setCodes": ["LTR"] }
```
Response: `{ "advisorId", "scores": [{ "cardName", "score": 0-100, "reason" }], "recommended": ["..."] }`.
`setCodes` lets a set-specific engine (Draftsim) load the right tables; when a known `lobbyId` is
supplied the server overrides it with the lobby's authoritative set codes (the body value is the
practice / no-lobby fallback). The heuristic engine ignores it.

**Auto-build / complete a deck** — `POST /api/deckbuild/auto-build`

```json
{ "lobbyId": "lob_1", "advisorId": "draftsim", "pool": ["Bear", "Bear", "..."],
  "basics": ["Plains", "Island", "Swamp", "Mountain", "Forest"],
  "lockedDeck": { "Bear": 2 }, "targetSize": 40, "setCodes": ["LTR"] }
```
Response: `{ "advisorId", "deckList": { "<name>": <count> }, "score": <number|null>, "archetype": <string|null> }`.
The client splits `deckList` into non-land cards + basic-land counts and applies it via the
deckbuilder's `setDeck`. `lockedDeck` empty = build fresh; non-empty = keep those cards and only fill
the rest (**heuristic** engine). The **draftsim** engine ignores `lockedDeck`/`targetSize` and always
returns a fresh 40-card limited build (23 nonland + 17 lands), matching the original Auto-Build.

## 3c. Cube Pack Source (WebSocket)

`UpdateLobbySettings` may replace the lobby's normal set source with a cube by sending the full
`cubeCards` name list plus `cubeName`, `packSize`, and `cubeBasicLandSetCode`. Duplicate names are
duplicate physical cards. The server resolves the entire list atomically; an unresolved card rejects
the update, and `cubeCards: []` clears cube mode. While a cube is active, `setCodes` changes,
`boosterDistribution`, and `chaosBoosters` are inert.

`LobbySettings` broadcasts only the public summary: `cubeName`, `cubeCardCount`, `packSize`, and
`cubePoolPlay`. The synthetic `CUBE` set is deliberately absent from `availableSets`. The server
rejects starting when the selected format would need more cards than the cube contains.

Saved cubes are account data, not lobby data: `/api/account/cubes` (see
[`accounts-and-persistence.md`](accounts-and-persistence.md)) stores them, and the lobby only ever
sees the expanded `cubeCards` list — which is what lets a guest, or a cube that was never saved
anywhere, play exactly the same way.

### Pool Play

`UpdateLobbySettings.cubePoolPlay` turns a **cube `SEALED`** lobby into Pool Play: nothing is dealt,
every player's `cardPool` is the entire cube, and copies are unlimited up to the 4-of cap. It is
rejected on a lobby with no cube or a non-`SEALED` format (rather than accepted and ignored), and
cleared automatically when the cube is cleared or the format changes away from `SEALED`.

`SealedPoolGenerated.poolPlay` tells the deckbuilder which pool semantics apply: with `poolPlay: true`
`cardPool` is the whole cube and adding a card must not consume it, so the client shows copies-in-deck
rather than copies-remaining. Consequences on the server side: the capacity check does not apply, the
"copies available in pool" validation is skipped (membership + the 4-of cap still hold), and the
sideboard is **not** derived from the pool — a Pool Play deck submits an empty sideboard, because
deriving `pool − maindeck` would seed the entire cube into the SIDEBOARD zone.

## 3d. Free-for-All Lobby Mode (WebSocket)

A lobby carries two orthogonal axes: the **format** (`SEALED` / `DRAFT` / `PREMADE_DECKS` / …,
how the card pool is built) and a new **mode** (`gameMode`: `TOURNAMENT` or `FREE_FOR_ALL`, what
happens once decks are in). `TOURNAMENT` runs the existing round-robin bracket of 2-player matches.
`FREE_FOR_ALL` (CR 806) seats **every** lobby player (2–6) in **one** multiplayer `GameSession` —
no rounds, no matches, no bracket. The two axes compose: any pool-building format + FFA = "draft (or
sealed, or premade), then one N-player game".

- **`CreateTournamentLobby` / `UpdateLobbySettings`** gain an optional `gameMode` (default
  `TOURNAMENT`). `LobbySettings.gameMode` echoes it. Switching a lobby to `FREE_FOR_ALL` caps
  `maxPlayers` at 6. `TWO_HEADED_GIANT` and `TEAM_VS_TEAM` are the two **team** modes (see below);
  both share the single-pod FFA lifecycle (one `GameSession`, play-again, standings).
- **AI seats at a pod.** `AddAiToLobby` works in every mode and every format: an AI is an ordinary
  seat, counted by the mode's own cap, and the engine AI reads a pod as N opposing sides
  (`ai/engine/Sides.kt`) and a 2HG team's pooled life as one total. `FreeForAllHandler` wires each AI
  seat to the pod's `GameSession` when the game starts and marks them ready between games, so only
  the humans are ever waited on. Where the AI's deck comes from follows the format: a generated pool
  is built by `buildAiPoolDeck`, and `PREMADE_DECKS` — which generates no pool — has one rolled by
  `RandomDeckResolver` at the moment the AI sits down, the same component and the same rule the quick
  lobby's `vsAi` seat has always used. Changing the lobby's format or `deckFormat` afterwards
  re-rolls it, since both decide what may be in it.
- **`SetLobbyAiDeck { playerId, spec }`** is the per-seat twin of `SetQuickGameAiDeck`: the host picks
  what *one* AI brings, in the same `AiDeckSpec` vocabulary (`auto` / `sets` / `deck`). Held per seat
  on `LobbyPlayerState.aiDeckSpec` and echoed back as `LobbyPlayerInfo.aiDeck` — an `AiDeckSpecView`
  summary (kind, sets, label, card count, designated commander), never the decklist itself, since
  lobby state re-broadcasts on every change. A `deck` spec carries an optional `commander`; the list
  is validated against the lobby's `deckFormat` on arrival, and the
  seat's deck is re-rolled immediately rather than at game start: the premade start gate wants every
  seat to have submitted, so the deck has to exist while the host is still looking at the lobby.
  Rejected outside `PREMADE_DECKS`, where the AI builds from the pool it was dealt.
- **Commander AI.** Every source picks its own commander. `auto` / `sets` build a singleton deck to
  the lobby's commander-shaped `deckFormat` — or to paper Commander when the Rules axis says Commander
  and no legality was set — and a limited pool is built from with `CommanderDeckGenerator.generateFromPool`.
  A `deck` spec is the one source that can be *missing* a commander, since the host chose the list; the
  server validates the full Commander deck on arrival and the lobby holds at its normal deck-submission
  gate until the choice exists. A seat whose pool holds no legal commander at all stays un-submitted
  rather than seating a deck the engine would refuse at init.
- **Attack rule.** The same two messages also carry an optional `attackMode` (default `MULTIPLE`),
  echoed by `LobbySettings.attackMode`, choosing which opponents creatures may attack in the FFA
  game (CR 802 / 803; CR 806.2b requires exactly one): `MULTIPLE` (any opponent), `LEFT`, or
  `RIGHT` (only the neighbour in that seat direction). It threads to the engine via
  `GameConfig.attackMode` → `GameState.attackMode`; the legal-action enumerator filters
  `validAttackTargets` and the engine rejects an out-of-seat declaration. Ignored in `TOURNAMENT`
  mode and in any two-player game (all three modes permit the sole opponent).
- **Team modes — Two-Headed Giant (CR 810) and Team vs. Team (CR 808).** Both split the pod into two
  even teams and share the same controls: an optional `randomTeams` (default **`true`**) and
  `teamAssignments` (playerId → team index `0`/`1`, full map), echoed by `LobbySettings`.
  `randomTeams = true` shuffles the seats into two even teams at game start, re-rolled each game;
  `false` uses the host's `teamAssignments`. At start the server resolves the partition
  (`EvenTeams.partition`): random, or the manual map balanced into even teams, falling back to
  seat-order grouping if the manual assignment can't form two equal teams. The result flows to
  `GameSession.teams` → `GameConfig.teams`. The two modes differ only in the engine `Format` chosen:
  - `TWO_HEADED_GIANT` — exactly four players (`Format.TwoHeadedGiant`): teams share one 30-life
    total, take shared turns, fight combined combat, and win/lose together.
  - `TEAM_VS_TEAM` — an even pod of 4/6/8 (`Format.TeamVsTeam`, i.e. 2v2/3v3/4v4): **nothing is
    shared** (CR 808.5). Each player keeps their own 20 life and their own turn, is eliminated
    individually (CR 104.3b), and a team loses only once all its members have left (CR 104.2c).
    `maxPlayers` caps at 8.

  The seat roster (`PlayerSeatInfo`) carries `teamIndex` for grouping and a game-level
  `teamSharedLife` flag (`true` for 2HG, `false` for Team vs. Team) so the client renders either a
  single shared-life team header or per-player life. Ignored outside a team mode.
- **Free mulligan.** A game that begins with more than two players (any FFA pod) uses the CR 800.6
  multiplayer mulligan: a player's *first* mulligan is free — it bottoms 0 cards and doesn't count
  toward the mulligan limit. This is engine-internal; the existing `MulliganDecision.cardsToPutOnBottom`
  already reflects the discounted count, so no client change is needed. Two-player games are
  unaffected (plain London Mulligan).
- **Start.** When the last deck is submitted (or the host starts a premade FFA lobby), the server
  creates one `GameSession` seating all players and broadcasts **`freeForAllGameStarting`**
  `{ lobbyId, gameSessionId, gameNumber, players: PlayerSeatInfo[] }` — the FFA counterpart of
  `tournamentMatchStarting`. Each recipient's roster flags its own seat (`isYou`); spectators get an
  all-`isYou=false` roster. `GameStarted` + the mulligan flow follow exactly as in any game.
- **Mid-game elimination.** Conceding (or a disconnect-forfeit) in a >2 pod concedes that seat and
  the game **continues** for the rest (CR 800.4a). The conceding player gets a personal
  **`playerEliminated`** `{ gameId, reason }` so their client shows defeat and returns to the pod
  standings while the table plays on; everyone else sees the seat drop out via the normal state
  rebroadcast (the eliminated player's `ClientPlayer.hasLost` is `true`). The game-wide `gameOver`
  only fires when ≤1 player remains (CR 104.2a).
- **Standings + play-again.** When the game ends, **`freeForAllGameComplete`**
  `{ lobbyId, standings: FfaStandingInfo[], gamesPlayed }` reports the **elimination order** as
  placements (`placement` 1 = winner, then last-eliminated, … back to first-eliminated). The pod
  stays open: each player sends `readyForNextRound` ("Play Again") and, when all connected players
  are ready, a new game (`gameNumber + 1`) starts with the same seats. Replays are saved per game as
  usual and browsable via the lobby's replay endpoint.
- Quick Game stays strictly 2-player (its `QuickGameLobby.MAX_PLAYERS` is untouched); FFA lives only
  in the tournament-lobby infrastructure. Its opponent seat is mutable: the host sends
  **`AddQuickGameAi`** to fill an open 1v1 seat with the built-in AI and **`RemoveQuickGameAi`** to
  reopen that same seat to a human. `QuickGameLobbyState.vsAi` reports the current occupant rather
  than a separate lobby kind; the create message's `vsAi` remains a shortcut for initially filling
  the seat.

## 4. Scenario Builder Payload (REST / HTTP)

The Scenario Builder lets any player construct an arbitrary board state and play it. It is a
production feature: `POST /api/scenarios` is **not** gated behind `game.dev-endpoints.enabled`
(the older `POST /api/dev/scenarios` is the dev-only equivalent and shares the same request
shape + builder via `ScenarioBuilderService` / `ScenarioSessionFactory`).

**Request: `POST /api/scenarios`** (`ScenarioRequest`)

```json
{
  "player1Name": "Me",
  "player2Name": "Also me",
  "player1": {
    "lifeTotal": 20,
    "hand": ["Lightning Bolt"],
    "battlefield": [
      { "name": "Grizzly Bears", "tapped": true, "counters": { "PLUS_ONE_PLUS_ONE": 2 } },
      { "name": "Pacifism", "attachedTo": "Grizzly Bears" }
    ],
    "graveyard": ["Mountain"],
    "exile": ["Swamp"],
    "library": ["Forest", "Forest"],
    "commanders": []
  },
  "player2": { "lifeTotal": 20, "battlefield": [{ "name": "Hill Giant" }] },
  "phase": "PRECOMBAT_MAIN",
  "activePlayer": 1,
  "mode": "SELF"
}
```

- `mode` selects how the opponent seat is filled: `SELF` (single-client hotseat / play against
  yourself — one token controls both seats), `AI` (engine AI, requires `game.ai.enabled`;
  `aiPlayer` 1|2 picks the seat), or `TWO_PLAYER` (two tokens). When omitted it is derived from
  `aiPlayer` for back-compat.
- Validation rejects unknown card names and (production) enforces per-zone + total card caps,
  returning `400` with `{ "errors": ["Unknown card: …", …] }`.
- `customCards` (optional) is a list of **Scryfall(-style) card objects, as JSON strings**. Argentum
  Assay compiles each into a real `CardDefinition` and registers it in a `CardRegistry` overlay for
  that session only, so its name can be used in any zone like a corpus card. Requires
  `game.dev-endpoints.enabled`; a card any of whose printed lines Assay cannot read is refused with
  the line that stopped it. The sources travel with the scenario (JSON, file, share link) — without
  them the names do not resolve and the request fails with `Unknown card`.

**Dev-only: `POST /api/dev/scenarios/assay`** (`AssayCompileRequest` → `AssayCompileResponse`) —
compile one pasted card without starting a game. Always `200`; a card that does not compile is the
answer, not an error.

```json
// request
{ "json": "{\"name\":\"Argentum Sentinel\",\"mana_cost\":\"{2}{W}\", …}" }

// response
{
  "cardName": "Argentum Sentinel",
  "compiled": true,
  "lines": [
    { "index": 0, "text": "Flying, vigilance", "verdict": "ROUND_TRIP" },
    { "index": 1, "text": "When ~ enters, draw a card.", "verdict": "ROUND_TRIP" }
  ],
  "declines": [],
  "warnings": [],
  "definition": { "name": "Argentum Sentinel", "…": "the compiled CardDefinition" }
}
```

`verdict` is the touchstone's own vocabulary (`ROUND_TRIP` | `VARIANT` | `DECLINED` | `AMBIGUOUS` |
`MISMATCH`); `printed` carries the canonical spelling of a `VARIANT`, and `explanation` carries
`assay explain`'s caret for a line that declined. Lines are shown normalized, where `~` is the
card's own name.

**Response** (`ScenarioResponse`)

```json
{
  "sessionId": "…",
  "player1": { "name": "Me", "token": "<token>", "playerId": "player-1" },
  "player2": { "name": "Also me", "token": "<token>", "playerId": "player-2" },
  "message": "Hotseat scenario created — you control both players.",
  "mode": "SELF"
}
```

The client joins by navigating to `/?token=<token>` (token-based connect). For `SELF`/`AI` a
single human token is returned (in `SELF` both `playerX.token` echo the same value); for
`TWO_PLAYER` the two tokens differ.

### Hotseat (`hotseat` on the client state)

`SELF` mode stamps a `HotseatControlComponent(controllerId)` on every seat, so the engine's
`GameState.actorFor(playerId)` routes input authority (decision delivery, legal-action
enumeration, per-action seat authorization, and hand visibility) for both seats to the single
connection — the non-turn-scoped generalization of the Mindslaver-style hijack seam. The
client state carries a boolean **`hotseat`** so the UI can show a "controlling both players"
banner and act for whichever seat currently holds priority (board actions ride the
server-provided `legalActions`, which already carry the acting seat; `SubmitDecision` is stamped
with `pendingDecision.playerId`; combat declarations with the active/defending seat).

### Compact replays (record inputs, re-simulate)

Replays are stored as **inputs, not snapshots**. A finished game is persisted as a `CompactReplay`:
its `setup` (RNG seed, decks, seat ids, format/teams/attack-mode) plus the ordered `actions` stream
that was applied. Because the engine is a pure, deterministic function and mints every entity id
from a state-threaded counter (never a UUID), `ReplayReconstructor` rebuilds the initial
`GameState` with that seed, folds the actions back through `ActionProcessor`, and re-runs the same
`SpectatorStateBuilder`/diff the live broadcast used — regenerating the exact `{initialSnapshot,
deltas}` stream the viewer consumes. This is kilobytes per game instead of a masked snapshot + a
per-frame delta + a full unmasked `GameState` per frame.

Decision ids are minted afresh each run (they are not part of the deterministic state), so a
recorded `SubmitDecision` is re-bound to the freshly created decision's id during reconstruction;
the choice payload (entity-id targets/cards) is unchanged, so the outcome is identical.

Payment modes are part of the recorded action payload. In particular, an equip action's
`alternativePayment.equipPayment` is serialized unchanged (`NORMAL` or `FREE_FIRST_EQUIP`); replay
and fork paths must preserve that field rather than re-deriving the mode from the current mana pool.
This keeps the legal-action domain, authoritative validation, actual payment, and deterministic
replay on the same external choice.

#### One store

Every replay — finished or still being recorded — is a row in `game_replays`, written by
`ReplayService` and nobody else. `ReplayStore` has two implementations: `JdbcReplayStore` when
accounts (and therefore a database) are enabled, and a bounded `InMemoryReplayStore` for a server
running without one. In-progress recordings are flushed to the store every few seconds by
`ReplayCheckpointFlusher` and picked back up on restart, which is what lets the Redis session blob
carry no replay data at all.

The flush is on a timer, not per action, so a crash can lose the tail of a recording. Splicing the
rest of the game onto that short prefix would produce a record of a game nobody played, so each
flush also writes a `resume_fingerprint` of the live position; on restore, `GameSession` compares it
against the recovered state and stops recording if they disagree, keeping the shorter honest replay.

#### Surviving deploys

An input log only reproduces a game while the engine folding it behaves as it did on the day — and
in this engine *cards are data the engine folds through*, so editing a card rewrites the past. Three
things address that:

| | What | Cost |
|---|---|---|
| `pinnedCards` | Compiled `CardDefinition` JSON for every card in the decks, overlaid on the live corpus during reconstruction (`ReplayCardPin` → a child `CardRegistry`). Card edits stop mattering; ability ids also stay stable, so recorded yields keep matching. Stored in its own write-once `pinned_cards` column, not in `data`, so the periodic flush doesn't rewrite it. | 7 KB gzipped on POR (34 definitions) up to ~40 KB on a modern set (113) — scales with deck variety, not game length, and is usually the largest part of a record |
| `checkpoints` | A cheap position fingerprint (`ReplayFingerprint`: entity counter, clock, turn/phase, zone sizes, life) every 20 actions. Catches *silent* drift — actions that still apply but no longer produce the board that was played — instead of rendering it. | ~30 bytes each |
| `presentation` | The `{initialSnapshot, deltas}` stream, materialized just after game over (the last moment we're provably on the recording build, on a background thread so it stays off the game-over path) and stored gzipped in its own column. A result rather than a recipe, so it renders regardless of engine changes. | ~62 KB gzipped for a 357-action game, ~160 KB for a 1650-action one — this one *does* scale with game length |

`ReplayService.viewerPayload` picks between them: re-simulate first, and if that comes back faithful
serve it (current view code, and "share frame as scenario" works because a real `GameState` exists);
if it diverged, serve the archived frames instead, flagged `degraded`. `ReplayFidelity` (`EXACT` /
`UNVERIFIED` / `DIVERGED`) and `stateReproducible` ride in the endpoint metadata, and the viewer
shows a **From archive** badge and hides the scenario buttons when the position can't be rebuilt.

`CompactReplay.version` is 5. Versions 1 through 4 remain historical labels; v4 is required for
actions carrying the explicitly versioned `PaymentStrategy.ExplicitV2` carrier and the joint floating
provenance semantics, while v5 is required for the ordered `PaymentStrategy.ExplicitV3` carrier and
its activation-cost ledger references. All additive fields default to empty and `persistenceJson`
ignores unknown keys, so records round-trip in both directions across a rolling deploy. `engineVersion` (the git sha,
passed to the backend image as `COMMIT_HASH`) is stamped on every record so a replay that stops
re-simulating can be traced to the build that recorded it.

**How big are they in practice?** `CompactReplaySizeBenchmark` (game-server, disabled by default)
plays whole games with purely random actions through the real `GameSession` recording path and
measures both payloads. On POR, ~1650 actions over ~32 turns per game:

| Payload | Raw JSON | Stored (gzip+base64) |
|---|---|---|
| Input log + pins + checkpoints (`data`) | ~237 KB | **~11 KB** (~7 B/action; ~7 KB of that is the 34 pinned card definitions) |
| Archived frame stream (`presentation`) | ~8 MB | **~160 KB** — ~14× the input log |

**POR is the cheap end of the range, though — don't plan capacity from it.** Portal's cards are
simple, so its definitions are small and there are few distinct ones. The pins scale with *deck
variety and card complexity*, not with game length, and on a modern set they dominate everything
else. Measured on a real 357-action ECL game (40-card decks, human vs AI), per stored column:

| Column | Stored (gzip+base64) | Scales with |
|---|---|---|
| `data` — input log + checkpoints | **~4.8 KB** | game length |
| `pinned_cards` — 113 definitions | **~40 KB** | deck variety / card complexity (fixed per game) |
| `presentation` — archived frames | **~62 KB** | game length |

So ~107 KB per finished game, and **the pins are the single largest cost** — bigger than the input log
by an order of magnitude, and unrelated to how long the game ran. Two consequences: budget per *game*,
not per *action*; and a 20-turn concession costs nearly as much as a 40-minute grind.

The input log itself stays genuinely tiny (~4.8 KB here, ~13× smaller than the archive), which is what
keeps re-simulation the primary path. But note that the size argument is no longer the *reason* it is
the record — with the pins counted, the recipe and the result are the same order of magnitude. The real
reason is that only the input log can rebuild a real `GameState`, which is what "share frame as
scenario" needs.

This split is also why the pins live in their own column rather than inside `data`: the flush rewrites
`data` every few seconds for the length of a game, and folding 40 KB of never-changing definitions into
each of those writes cost ~12× more per flush than the action log itself. See `V11__replay_pins_write_once.sql`.

Random play is action-heavy (it passes priority constantly and rarely closes out a game), so real
AI/human games tend to have shorter action logs — but the same or larger pins. Run it with:

```bash
./gradlew :game-server:test --tests "*.CompactReplaySizeBenchmark" -Dbenchmark=true -DbenchmarkGames=40 -DbenchmarkSet=BLB
```

### "Share frame as scenario" (replay)

The replay viewer can also reproduce an **exact full-state snapshot** — stack, targets, floating
effects, mana, counters and all trackers, not just the public board — by re-simulating the compact
replay up to the requested frame (so no full `GameState` is stored per frame). Two entry points:

- **Share as scenario** → copies a *short* link that only references the stored frame:
  `/scenario?replay=<gameId>&frame=<n>`. Opening it `POST`s to `/api/scenarios/from-replay-frame`
  (`{gameId, frame, mode?}`), which calls `ReplayService.reconstructStateAt(gameId, frame)` and
  injects the result into a fresh hotseat session (`mode=SELF` default).
- **Download** → saves the frame's full state as a JSON file
  (`GET /api/public/replays/{gameId}/frames/{frame}/full-state`). Reload it locally from the
  builder's **Load file** button, which `POST`s the file to `/api/scenarios/from-state` (a raw
  serialized `GameState` body) and jumps in. "Load file" also accepts a **name-based** scenario
  JSON (like the `manual-scenarios/*.json`), loading it into the editable builder instead.

A snapshot is exact but **not editable** in the card-search builder; the builder's own name-based
`?s=` share remains for authoring/editing. The engine `GameState` is (de)serialized with
`persistenceJson` (`allowStructuredMapKeys` — `zones` is keyed by `ZoneKey`).

## Gym structured decision observations

The Gym contract is currently `argentum-gym-contract@v1.26-repeat-count-domain`. The preceding
`argentum-gym-contract@v1.25-target-payment-domain` and
`argentum-gym-contract@v1.24-mana-color-domain` identifiers remain historical and must not be
interpreted as the current observation schema.
`TrainingObservation.pendingDecision` is a perspective-safe `PendingDecisionView`. When the
perspective owns a complex decision, `structuredDomain` contains a typed, versioned domain copied
from the authoritative Rules decision. The opponent receives the existing generic view with no
domain or private candidates.

Each `LegalActionView` publishes `requiredPayloadFields`, an ordered and deduplicated list of the
structured JSON fields the acting controller must provide. `requiresStructuredAction` is exactly the
non-empty projection of that list. The list is structural and remains published for unaffordable
actions; an explicitly empty choice such as `additionalCostPayment` for a zero-card sacrifice is
still required. The trusted server validates against the Gym-owned canonical requirement projection
over the Rules-owned `LegalAction` contract and never infers a missing value from presentation
fields.

Mana actions that require `manaColorChoice` additionally publish `availableManaColors`. This is the
complete, Rules-owned, perspective-safe legal color domain in canonical WUBRG order. It is non-null
for every such action; an empty list is an authoritative empty domain. Actions without a mana-color
choice carry null. Policies select only from this list and never reconstruct a `ManaColorSet` from
action semantics, commander zones, or other observation fields.

At the trusted Gym submission boundary, a required choice must be non-null and a member of the
registered Rules domain; an empty domain accepts no value. A color choice on an action that does
not require one, duplicate domain entries, and drift between the registered and freshly
re-enumerated Rules domain are rejected before mutation. The legacy Rules executor's fallback
color selection is therefore unreachable through this trusted path.

The domain hierarchy covers targets, card selection, modes, distribution, ordering, pile splitting,
library search, library reorder, combat resolution, mana-source selection, replacement choices and
budget modals. Simple one-mode and single-card choices remain flat `legalActions`. The obsolete
legacy `AssignDamageDecision` shape is not projected as a modern domain; current combat uses the
complete `CombatResolutionDecision` graph.

The client must submit a complete `DecisionResponse` through the decision endpoint. It must not
infer candidates from hidden zones or compute legality locally. Rules validates the response against
the pending decision. `decisionId` is a routing value and is not part of `stateDigest`; candidate
sets are canonicalized while ordered library sequences remain ordered. The same DTOs and JSON
configuration are used by the JVM service and HTTP server.

### DeclareAttackers choice domain (AttackDeclarationDomainV2)

`LegalActionView.attackDeclarationDomain` is present only for `DeclareAttackers` and is a
versioned, complete public choice domain. Its current `version` is `2`, and its fields are:

- `attackerOrder`: the Rules-owned candidate sequence. It is the authoritative order for every
  attacker-related list in this domain; it is not reconstructed from a map, set, `EntityId`, or
  allocation order.

- `attackerToDefenders`: every attacker maps to every Rules-resolved legal defender reference;
  this includes individual attack/defender legality and state-resolved Taunt/Goad defender
  restrictions. It is not a convenient global attacker list plus a second client-side filter.
- `mandatoryAttackers`: the complete Rules-resolved set required by MustAttack, MustAttackThisTurn,
  projected MustAttack, and Goad requirements.
- `canDeclareZeroAttackers`: explicit legality of the empty declaration, derived from the same
  Rules pre-tax authority; an empty relation is not an implicit approval of zero attackers.
- `maxAttackers`: the Rules-resolved global attacker cap, or `null` when no cap applies.
- `coAttackerRequirements`: per-attacker requirements whose `anyOf` lists contain the concrete,
  already Rules-resolved companion IDs. Gym never reconstructs this filter.
- `bandConstraints`: the complete per-defender partition of `bandingAttackersByDefender` and
  `nonBandingAttackersByDefender`. Each attacker/defender relation appears exactly once. Rules
  semantics remain authoritative for band size, common defender, declared membership, at-most-one
  non-banding member, and no multi-band membership.

The DTO is a pure projection of the Rules-owned certificate. The mixed defender universe is ordered
by active opponent seat order first, followed by attackable battlefield objects in the shared
combat-object order (`objectIdentityStamps`, with `BattlefieldEntryTimestampComponent.timestamp`
as the bounded compatibility fallback). Every `attackerToDefenders[attacker]` list is a filtered
subsequence of that one Rules-owned defender order. Mandatory attackers, co-attacker `anyOf`
lists, and band partitions preserve the corresponding `attackerOrder` ranks; requirement
multiplicity remains intact.

The mapper preserves those sequences and may use insertion-ordered containers only to serialize
them. It never chooses an attacker or defender order. Missing or duplicate combat ranks and
unaddressable references fail the complete domain closed. Before Rules execution, the trusted Gym
boundary validates the submitted `DeclareAttackers` against the exact certificate snapshot
registered on the selected `LegalAction`; it does not rebuild the domain from the current
`GameState`. Rules then performs its existing stateful pre-tax Magic validation, and Attack Tax
remains a later explicit payment decision boundary rather than an implicit Gym choice.

`AttackDeclarationDomainV1` remains historical codec material only. The current strict live path
publishes and accepts `AttackDeclarationDomainV2`; a V1 live request or unknown future version is
unsupported and is never silently reinterpreted.

If the complete certificate cannot be projected, or any reference is not perspective-addressable,
the whole trusted observation fails closed with `ATTACK_DECLARATION_DOMAIN_UNSUPPORTED`. The Gym
never exposes a silently reduced attacker/defender relation. The required payload contract is
unchanged: a DeclareAttackers submission must explicitly include the ordered fields
`["attackers", "bands"]`, including explicit empty choices. This change is limited to
DeclareAttackers; blockers, blocker ordering, damage assignment, cards, decks, and frontend
contracts are unchanged.

The explicit replay-wire audit confirmed that `CompactReplay` serializes only the existing
`GameAction` carrier (`attackers` and `bands`) plus its setup/yield/pin/checkpoint metadata; it
does not contain `LegalActionView`, either attack-domain DTO, `schemaHash`, or any observation
domain. Replay reconstruction therefore regenerates the Rules attack domain/order from rebuilt
state and validates the recorded semantic `DeclareAttackers` action. It remains independent of Gym
observation data and does not itself require a replay bump; the current replay label is v5 because
the payment action carrier changed.

### DeclareBlockers choice domain (BlockerDeclarationDomainV1)

`LegalActionView.blockerDeclarationDomain` is present only for a supported `DeclareBlockers`
action. It is version `1` and is a perspective-safe projection of the Rules-owned
`RulesBlockerDeclarationDomain` certificate. The DTO is complete for the current supported
blocker machinery; if Rules cannot resolve a represented constraint or a public reference cannot
be addressed by the defending player, the whole action domain fails closed with
`BLOCKER_DECLARATION_DOMAIN_UNSUPPORTED`.

The fields are:

- `blockerOrder`: the producer-owned canonical order of all current blocker candidates. It is not
  an ordering choice exposed to the controller.
- `attackerOrder`: the producer-owned canonical order of all current attacking entities.
- `blockerToAttackers`: for each blocker candidate, the complete Rules-resolved set of attacking
  entities that blocker may block. Pairwise evasion, controller/team, projected characteristics,
  and blocker restrictions have already been resolved by Rules.
- `maxAttackersByBlocker`: the maximum number of attacking entities each blocker may block in
  this declaration, including the current supported direct `CanBlockAnyNumber` and projected
  additional-capacity forms. An active conditional or granted shape that the producer cannot
  resolve into this bound makes the whole blocker domain unsupported.
- `minBlockersByAttacker` and `maxBlockersByAttacker`: resolved per-attacker declaration bounds.
  A minimum applies only when that attacker is chosen to be blocked; an attacker may still remain
  unblocked. Menace and the supported printed minimum forms, together with supported printed,
  conditional, direct-granted, and projected maximum forms, are resolved here rather than
  reconstructed by Gym. An active unsupported filter or grant fails closed instead of being
  omitted from the certificate.
- `globalMaxBlockers`: the active global blocker cap, or `null` when no such cap applies.
- `coBlockerRequirements`: for each restricted blocker, all resolved co-blocker groups. Each group
  is an `eligibleCoBlockers` any-of list, and every group must be satisfied when that blocker is
  selected.
- `requirements`: the resolved CR 509.1c requirement instances. This is a multiset represented
  as a list: identical `BlockSpecific`, `BlockOneOf`, `AttackerMustBeBlockedIfAble`,
  `AttackerMustBeBlockedByAll`, or `BlockerMustBlockIfAble` entries are retained as separate
  instances and each occurrence counts independently. Current Lure-style all-able effects are
  resolved into blocker-scoped `BlockOneOf` instances, so multiple eligible blockers and repeated
  effects remain explicit rather than collapsing into one attacker relation.
- `minimumSatisfiedRequirementCount`: the exact Rules-owned maximum number of those requirement
  instances that can be satisfied simultaneously without violating the published 509.1a-c
  restrictions and requirements. Relations that would require a blocking cost are not included
  in this maximum: CR 509.1c does not require paying that cost merely to obey more requirements.
  A submitted declaration must satisfy at least this count. Gym does not compute a deduplicated
  matching or interpret Provoke as an unconditional pin.
- `canDeclareZeroBlockers`: the direct Rules-owned result for the empty declaration under the
  complete 509.1a-c certificate. It is explicit and is not inferred from an empty candidate list
  or from the absence of a legacy mandatory-assignment hint.

`blockerToAttackers` still contains every pairwise legal assignment, including assignments that
will require a later blocking-cost decision. The cost-free relation used internally by Rules to
evaluate the 509.1c maximum is not part of `BlockerDeclarationDomainV1`; blocking costs remain a
separate 509.1d-f continuation after a selected assignment is accepted.

The defender constructs the existing semantic carrier directly from this domain:

```text
BlockerDeclarationDomainV1
  -> { blockerEntityId: [attackerEntityId, ...], ... }
  -> DeclareBlockers.blockers
```

The public mapper preserves the producer's canonical order and never sorts by `EntityId.value`,
hash iteration, allocation order, UUID, or hidden-state-dependent metadata. Assignment maps and
per-blocker attacker lists are semantically unordered gameplay relations; the published order is
only the deterministic contract representation. Requirement-list order and multiplicity are
semantic and are preserved exactly. A future/unknown domain version is rejected rather than
interpreted as V1.

The strict Gym boundary validates the submitted blocker map against the exact Rules certificate
registered for the selected action before calling `ActionProcessor`. It checks shape, public
membership, pair relations, per-blocker and per-attacker bounds, global/co-blocker restrictions,
empty-declaration legality, and the published requirement-instance threshold. Rules then performs
its stateful final checks and remains authoritative for commitment and events. A rejected or
stale submission is not a gameplay transition and cannot advance the state, RNG, continuation,
replay, turn/priority, or accepted-transition counters. Strict execution also rejects the old
action handle when the live Rules producer can no longer publish a supported certificate; it
cannot fall through to the legacy blocker path.

Blocking costs are intentionally not part of this assignment domain. Under the existing Rules
flow, a domain-valid assignment may be followed by a separate externally controlled blocking-cost
decision under CR 509.1d-f before the block is committed. CompactReplay remains unchanged: it
stores the semantic `DeclareBlockers.blockers` action carrier, and reconstruction regenerates the
domain from the deterministic Rules state instead of recording the observation DTO.

The domain publishes only public battlefield/combat information addressable to the acting
defender. It does not expose hidden hand/library/exile identity, unrecognized face-down identity,
raw `GameState`, `CardRegistry`, evaluator internals, private provenance, or future policy
choices.

### Historical action-level mana payment (PaymentDomainV4 / PaymentPlanV2)

An affordable structured `ActivateAbility`, ordinary fixed-cost `CastSpell`, plain fixed-cost
`CastWithKicker`, or plain fixed-cost `CycleCard` whose action-level mana cost is published in
`LegalActionView.manaCost` also publishes `LegalActionView.paymentDomain`. This domain is version 4
and is complete for the supported slice: ordinary fixed colored/colorless/generic costs, unrestricted
floating mana, ordinary tap sources, explicit single-output color selection, deterministic fixed
multi-mana bundles, deterministic fixed self-damage side effects bound to the selected
`manaAbilityKey`, and multiple source combinations. A mixed source is publishable only when every
currently legal payment-relevant mana ability has a complete exact production profile and side-effect
certificate. A payable action whose complete V4 domain
cannot be published fails closed with `PAYMENT_DOMAIN_UNSUPPORTED`; it never falls back to an
engine-selected payment policy at the trusted Gym boundary.
For `CycleCard`, publication is limited to a plain Cycling keyword whose authoritative cost is
exactly fixed ordinary mana; the Gym carrier must submit `PaymentStrategy.ExplicitV2` with a complete
`PaymentPlanV2`. X, hybrid/alternative, additional, dynamic, restricted, typed-cycling, and other
unrepresentable Cycling shapes remain unsupported and publish no domain. Cycling uses the same
non-null activated-ability payment context for enumeration, domain certification, validation, and
Rules materialization; `ability = null` in that context does not disable mana restrictions.
`autoPaySuggestion` is not part of this action-level domain and is never a policy input.
Non-mana structured cast choices may be submitted alongside the plan when they do not alter the
effective mana cost or the published source set; cost-changing or source-affecting choices remain
fail-closed.
For the existing `ActivateAbility.costPayment` field, there is one deliberately narrow additional-
cost exception: when the authoritative effective ability cost contains one fixed ordinary mana atom
(including `{0}`), optional `TapSelf` and/or `SacrificeSelf`, and the explicitly certified
`PayLife(CommanderColorIdentityCount)` expression, Rules may certify the source-bound payment for
Gym. The public `LegalActionView` already provides `sourceEntityId`; the existing
`requiredPayloadFields` requires `costPayment` when the enumerated action has `additionalCostInfo`,
and the observation/trusted Gym seam adds the same requirement for a certified TapSelf-only action
even when no choice metadata is needed. The controller materializes the canonical acknowledgement
as `tappedPermanents: [sourceEntityId]` and/or `sacrificedPermanents: [sourceEntityId]`; every other
`AdditionalCostPayment` field must remain at its empty/default value. The life expression adds no
payload field and is not a player decision: Rules resolves and pays it through the existing
authoritative life-payment path. This is not a selection domain: Rules derives the expected value
from the authoritative effective `AbilityCost` plus the activated source ID, then checks exact
equality before any mana, tap, sacrifice, or life mutation. `PaymentDomainV4` remains mana-only.
On the trusted A5 external-policy path, a zero-mana action uses an explicit
`PaymentStrategy.ExplicitV2` object with an empty `PaymentPlanV2`; canonical `ManaCost.ZERO` has no
cost symbols, so its `PaymentPlanV2.spendAllocation.costUnits` list is empty. The general Gym
contract retains the existing representable `PaymentStrategy.Explicit` / `PaymentPlanV1`
compatibility path. Neither path makes payment implicit or permits an implicit source choice, and
no changes to the historical serialized DTO, schema hash, or replay version are made by this
contract. The public payment and replay schemas for the current contract are versioned separately;
the historical payment/replay schemas are unchanged by the CycleCard, deterministic mana
side-effect, and source-bound activated self-cost slices. `PaymentSourceActivationDomain.manaAbilityKey`
remains the structural identity
of the entire selected activated ability (excluding runtime `id` and `descriptionOverride`); the
side-effect certificate is an internal closure proof, not a second execution authority.
### Pending mana payment (ManaSourcesDomain V3)

`ManaSourcesDomain` V3 publishes one complete `PaymentDomainV5` for a supported pending payment.
That domain names every qualified source activation, production choice, initial-pool bucket, atomic
cost unit, and legal allocation. The trusted responder supplies
`ManaSourcesSelectedResponse.paymentPlan: PaymentPlanV3`; `GameGymEnv` validates the plan against
the public domain before it reaches Rules, and Rules validates the same V3 program again before
executing it.

The durable response excludes the live `decisionId`, source-only `selectedSources`, and `autoPay`.
`selectedSources` and `autoPay` remain legacy engine fields for non-Gym clients, but they cannot
satisfy a trusted pending payment. `autoPaySuggestion` is not published in the V3 Gym domain.

`CompactReplay` V6 is the durable engine carrier for a pending `PaymentPlanV3`. V5 and earlier
records reject that carrier rather than silently decoding it as a legacy source-only response; V6
retains the V4 transition-state fingerprint because the new semantics are in the ordered replay
input stream.

The V3 slice supports only pending payments that the existing V5 qualifier can represent and that
retain an explicit unpaid branch. A source or pool shape that cannot produce one complete V5 domain,
Waterbend's separate tap-to-reduce choice, composite Ward's later non-mana costs, and the historical
targeted optional-payment continuation with no unpaid response publish no structured domain and fail
closed at the trusted Gym boundary. Rules also rejects an explicit V3 response for Waterbend or a
composite Ward before it can execute a partial payment. These cases never fall back to a solver,
source order, or implicit mana color.

The controller submits representable historical choices inside `PaymentStrategy.Explicit.paymentPlan`.
When the current domain exposes joint floating buckets, it submits the versioned
`PaymentStrategy.ExplicitV2.paymentPlan`; V1 is never reinterpreted with an optional subtype field:

```json
{
  "paymentStrategy": {
    "type": "Explicit",
    "manaAbilitiesToActivate": [],
    "paymentPlan": {
      "sourceActivations": [
        {
          "sourceId": "ent-land",
          "manaAbilityKey": "<stable structural ability identity>",
          "productionChoice": { "producedColor": "BLACK", "amount": 1 }
        }
      ],
      "poolSpend": { "black": 0, "green": 0, "colorless": 0 },
      "spendAllocation": {
        "costUnits": [
          { "symbolIndex": 0, "spends": [{ "sourceId": "ent-land", "amount": 1 }] }
        ],
        "x": [],
        "restricted": [],
        "riderBearingSourceIds": []
      }
    }
  }
}
```

For a deterministic multi-output mana ability, `productionChoice.fixedOutputs` is the only
canonical representation. It is an ordered list with indexes exactly `0..n-1`, at least two
entries, and `amount: 1` on every entry; `producedColor` must equal the first output's color. Each
source spend must then carry the matching `sourceOutputIndex`. A bundle-capable source is never
accepted in the legacy single-output form (`fixedOutputs: null`), and legacy single-output spends
must not carry `sourceOutputIndex`.

For example, a source whose one tap deterministically produces `{B}{G}` publishes:

```json
{
  "producedColor": "BLACK",
  "amount": 1,
  "fixedOutputs": [
    { "index": 0, "color": "BLACK", "amount": 1 },
    { "index": 1, "color": "GREEN", "amount": 1 }
  ]
}
```

The submitted allocation chooses each consumed output explicitly. Any unconsumed fixed output is
materialized into the floating pool with its source and subtype provenance; it is not discarded or
reallocated by Rules. Single-output selectable-color abilities remain represented by the legacy
`fixedOutputs: null` form. Unresolved color choices inside a multi-output ability, runtime
production modifiers, dynamic amounts, restrictions, riders, and secondary costs remain
fail-closed.

`manaAbilityKey` is a stable public identity derived from the current ability structure; a
runtime-generated `AbilityId` is not accepted as a substitute. The plan must explicitly choose the
source activation, production color, unrestricted pool units, and cost-symbol allocation. Rules
validates and materializes those choices but does not fill in a missing ability, color, floating
unit, generic/hybrid/X allocation, restriction bucket, or rider-bearing path. The trusted Gym
boundary rejects `AutoPay`, `FromPool`, and the legacy source-ID-only form for these actions.

Choice-bearing secondary costs — selecting one of several permanents or cards, variable quantities,
target/domain-dependent payments, and other sacrifice/tap choices — plus bonus/restricted mana,
riders, hybrid/X shapes, and other exotic payment forms remain fail-closed. Such a reachable shape
is an unsupported Gym diagnostic, not a partial domain. A deterministic `TapSelf` or `SacrificeSelf`
is supported only through the source-bound `costPayment` acknowledgement described above; Rules does
not infer it when the externally controlled Explicit path requires the field.

#### Historical PaymentDomainV3 floating provenance

`PaymentPoolDomainV3` is retained for historical payloads and may additionally contain one nullable
`certifiedFloatingMana` value. It is
the canonical representation for a Rules-certified homogeneous pool: the common color and
stored subtype set are published once, while `sourceBuckets` partitions the current units by
producing source.

```json
{
  "poolColor": "GREEN",
  "sourceSubtypes": ["Forest"],
  "sourceBuckets": [
    { "sourceId": "ent-forest-a", "amount": 1 },
    { "sourceId": "ent-forest-b", "amount": 1 }
  ]
}
```

The Rules-owned classifier publishes this value only when it proves from the authoritative pool state
that: unrestricted total is positive; exactly one color contains that total; restricted mana is empty;
positive source counters partition the total; and every recorded subtype counter equals the total.
That proof may use the existing homogeneous aggregate shape or a COMPLETE source×color map; the
detail map is never reconstructed from aggregates. Therefore every unit has the same stored subtype
set when subtype metadata is available, and source identity is the only remaining partition. The
subtype list and bucket list are canonicalized by stable content; their input collection order is not
semantic. `NoTrackedProvenance` describes missing metadata, not the absence of a Magic source identity.
Any tracked state that cannot be certified is unsupported and the complete action-level domain fails
closed. Every newly published floating bucket source identity must pass the existing perspective-safe
`Visibility` authority; an unpublishable strategically distinct bucket invalidates the whole domain.
Existing future `sourceActivations` visibility remains a separate concern.

For a genuinely multi-color pool, historical V3 instead sets `certifiedHeterogeneousFloatingMana` and leaves
`certifiedFloatingMana` null. Its `sourceColorBuckets` list is the Rules-owned source×color matrix:

```json
{
  "sourceColorBuckets": [
    { "sourceId": "ent-source-a", "poolColor": "BLACK", "amount": 1 },
    { "sourceId": "ent-source-b", "poolColor": "GREEN", "amount": 1 },
    { "sourceId": "ent-source-c", "poolColor": "GREEN", "amount": 2 }
  ],
  "sourceSubtypes": []
}
```

The two certification fields are an explicit one-of. An incomplete or inconsistent detail map is
never ignored or reconstructed from aggregate totals, source profiles, iteration order, or heuristics.

`PaymentPlanV1` remains unchanged, with its existing
`ManaSpendReference.floatingSourceId`. `sourceId` continues to mean a freshly activated source
output; `floatingSourceId` plus `poolColor` means an already-floating unit from a certified bucket.
`PoolSpend` remains only the aggregate color checksum. For a multi-bucket certified pool, every
floating spend must name its exact bucket; the validator aggregates all such references, checks the
complete plan against the certified bucket capacities and `PoolSpend`, and only then materializes the
selected source counts. A unique single-bucket pool may retain the legacy source-less pool reference.
Rules decrements only selected source buckets and the common subtype counters, preserving unselected
sources; it never delegates the external choice to greedy `consumeProvenance()`.

#### Historical PaymentDomainV4 joint buckets

`PaymentPoolDomainV4.certifiedFloatingBuckets` is the single canonical representation for both
homogeneous and heterogeneous pools. Each row is a complete Rules-issued semantic key, and rows
with the same source and color but different production-time subtype snapshots remain distinct:

```json
{
  "certifiedFloatingBuckets": [
    { "sourceId": "ent-forest", "poolColor": "GREEN", "sourceSubtypes": ["Forest"], "amount": 2 },
    { "sourceId": "ent-forest", "poolColor": "GREEN", "sourceSubtypes": [], "amount": 1 },
    { "sourceId": "ent-swamp", "poolColor": "BLACK", "sourceSubtypes": ["Swamp"], "amount": 1 }
  ]
}
```

`sourceSubtypes: []` means a known empty production snapshot. Rows are canonicalized by source,
color, and sorted subtype names. An incomplete aggregate or legacy detail path is not published.
The V4 publisher also requires authoritative Rules known-information metadata proving that the
stored production snapshot is disclosed to the acting player; source addressability or the current
`CardComponent` alone is not enough. If that proof is absent, the complete payment domain fails
closed.

The V2 plan echoes the complete row key and amount in its floating spend reference. The server
checks that the key is currently certified and never merges a client-supplied subtype list into
Rules state. V1 remains accepted only where `(floatingSourceId, poolColor)` identifies exactly one
joint bucket; if both `{Forest}` and `{}` exist for the pair, V1 rejects and V2 is required.

The preceding Gym observation schemas were
`argentum-gym-contract@v1.25-target-payment-domain` and
`argentum-gym-contract@v1.24-mana-color-domain`. Current observations use
`argentum-gym-contract@v1.26-repeat-count-domain`. `PaymentDomainV4` remains a historical
payment-domain DTO. A client must compare the hash before interpreting the current payment domain
and fail closed on mismatch. Historical V4 payloads remain decodable only through their historical
DTO and are not reinterpreted as V5.

The authoritative `GameState` persists the joint bucket map, completeness marker, and known-
information metadata. State digests and the v4 transition-semantic replay fingerprint bind those
fields. CompactReplay v4 is required for actions carrying the historical serialized `ExplicitV2`
discriminator; old v1-v4 action payloads remain decodable under their historical labels.

### Current action-level mana payment (PaymentDomainV5 / PaymentPlanV3)

Current observations publish `PaymentDomainV5` for the complete ordered paid-mana-source slice.
The domain contains atomic outer-cost units, fungible initial-pool bucket capacities, Rules-owned
source/ability options, exact fixed activation-cost units, legal activation-cost orders, and the
public outer-life constraint. It contains no policy-selected activation list, allocation, Gym
identity hash, or automatic payment suggestion.

The trusted external-policy path must submit `PaymentStrategy.ExplicitV3` with a complete
`PaymentPlanV3`. The plan's activation list is ordered and list-indexed; each activation-cost
allocation and outer allocation consumes one shared Rules ledger. Activation outputs are available
only after their node succeeds, and references may target only initial-pool buckets or outputs of
earlier nodes. Rules rejects missing, duplicate, forward, self-funding, stale, or otherwise
unsupported plans before mutation and never falls back to AutoPay/native selection.

`PaymentDomainV5` is published only when the complete discovered source set is representable and
the certified timing, permission, production, side-effect, and ordering contracts hold. A source
that cannot be represented makes the complete action domain unsupported; it is never silently
omitted. Fixed self-damage may reach life 0 or a negative intermediate total during the mana
ability window; state-based actions are not simulated until the ordinary priority boundary. When
the outer action reserves positive PayLife, the public fixed-self-damage budget is the current
life total minus that reservation; with no outer PayLife reservation the budget is unbounded.

CompactReplay v5 is required for the persisted `ExplicitV3` action carrier; current CompactReplay
v6 additionally carries a pending `ManaSourcesSelectedResponse.paymentPlan`. Both preserve the v4
transition-semantic fingerprint and checkpoint semantics while recording the complete V3 program as
part of the action stream. The replay payload does not serialize a Gym-domain hash or observation
schema identity. Replay v4 plus `ExplicitV3`, and replay v5 plus a pending V3 plan, are rejected;
unknown future replay versions fail closed before action deserialization or reinterpretation.

### Target-bound activated-ability payment (TargetPaymentDomainV1)

An activated ability with exactly one mandatory controller-chosen Battlefield permanent target may
publish `LegalActionView.targetPaymentDomain` when the Rules-owned
`ActivatedAbilityCostCalculator` proves that the effective mana cost differs for at least one
public target candidate. The relation is intentionally limited to a finite, complete candidate
set with one target per binding. Multi-target, optional or unlimited cardinality, dynamic target
counts, non-permanent targets, target combinations, mode-coupled costs, unresolved alternative or
additional payment choices, and any other unproven shape fail closed as a complete relation.

`TargetPaymentDomainV1.targetBindings` has exactly one entry for every candidate in the already
published `ActionTargetDomainV1` requirement, in that same order. Each binding contains an
`affordable` result recomputed for that exact target-bound action and a non-null
`PaymentDomainV5`. The nested `PaymentDomainV5.requiredCost` is the sole target-bound mana-cost
authority; the parent action sets `manaCost` and `paymentDomain` to null and reports affordability
as the existence of at least one affordable binding. A complete V5 domain is still published for
an unaffordable binding, while an unrepresentable binding makes the entire target-payment relation
unsupported.

`targetPaymentDomain` is included in both the wire observation and the semantic action
canonicalization. Its binding sequence is preserved exactly; `ObservationCanonicalizer` does not
invent a second ordering. Any nested V5 cost, source capability, or allocation-domain change is
therefore digest-relevant. This additive observation relation does not change `PaymentDomainV5`,
`PaymentPlanV3`, the executed `GameAction` carrier, or CompactReplay v5.

On the trusted Gym submission path, the opaque action ID is bound to the `LegalActionView` from the
cached registered observation. The server re-enumerates the current Rules action and builds a fresh
public target/payment relation without replacing the cached snapshot, allocating new action IDs, or
advancing any observation cursor. The registered and current target domains and complete target-
payment relations must be semantically equal before the submission is interpreted.

The submitted permanent target selects exactly one binding from that relation. The binding must be
currently affordable, the submission must carry `PaymentStrategy.ExplicitV3` with a complete
`PaymentPlanV3`, and the plan is preflighted against the selected current binding. The Rules-side
recomputation must equal `currentBinding.paymentDomain.requiredCost`; that binding cost is the sole
target-bound mana-cost input passed to the shared `PaymentPlanValidator.validateV3` preflight. The
validator performs no payment selection, and no state mutation occurs before it accepts the plan.

Missing, duplicate, or off-domain targets; an unaffordable binding; registered/current target or
relation drift; nested V5 or required-cost drift; a plan from another target binding; malformed
ExplicitV3 data; or any failed V3 preflight is rejected atomically before Rules execution, events,
step advancement, or replay recording. The trusted path never falls back to AutoPay, FromPool,
legacy Explicit source lists, or a different target binding.
