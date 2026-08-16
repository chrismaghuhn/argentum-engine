# ARG-DECK-01 — Exact Akiri vs Chevill Lists + Argentum Coverage Audit

## Executive result

DECK_DESIGN: **PASS**

COMMANDER_LEGALITY: **PASS**

COMBO_SAFETY: **PASS — STATIC_COMBO_SCAN**

ARGENTUM_COVERAGE: **PARTIAL**

ENGINE_TRACTABILITY: **PARTIAL**

Overall: **DECK_01_READY_FOR_PROVISIONAL_LOCK**

The exact pair is strong enough to carry into the later A2/A8 and paired-seed validation gates. The recommendation is the Argentum-Friendly variant as the first executable target, while preserving the Curriculum Ideal lists as the strategic reference. This is a provisional curriculum lock, not a claim that either commander is currently executable in Argentum.

No cards, mechanics, rules-engine code, AI, gym, observation, or replay code were implemented in this milestone.

## Audit baseline

| field | value |
|---|---|
| audit timestamp | 2026-08-15T22:04:04.0056553+02:00 |
| ARGENTUM_UPSTREAM_SHA | `d66a5d7f1b46b0ed8891c34ccfe163d491c4ff3d` |
| FORK_BASE_SHA before synchronization | `d7a6238ed90f3a5a14d019eba5f6f118c6baf966` |
| audit source SHA | `31853cfe91b52718dc3fb67f159e6267d9c5fcc1` |
| audit branch | `agent/deck-01-akiri-chevill-coverage` |
| synchronized fork head | `31853cfe91b52718dc3fb67f159e6267d9c5fcc1` |
| official format snapshot | undated live pages retrieved 2026-08-15 |
| official B&R snapshot | 2026-02-09 announcement; live banned list retrieved 2026-08-15 |
| bracket-policy snapshot | 2026-02-09 beta update |
| Game Changer snapshot | 2025-10-21 list, with 2026-02-09 Biorhythm/Farewell update |

The audit worktree was created from the fork after a normal merge of upstream. The active `agent/a2-1-commander-zone-conformance` worktree remained untouched. The audit is pinned to the merge source SHA above; later upstream changes require a fresh audit.

## Audit snapshot vs post-audit status

### AUDIT SNAPSHOT

The coverage classifications, percentages, mechanic inventory, and persisted
CSV artifacts in this document describe the state at the pinned ARG-DECK-01
source SHA `31853cfe91b52718dc3fb67f159e6267d9c5fcc1`. They are reproducibility
artifacts, not silently refreshed claims about current `main`.

### POST-AUDIT STATUS

ARG-DECK-02 / [PR #5](https://github.com/chrismaghuhn/argentum-engine/pull/5)
was subsequently merged into the fork main at
`5faeccb61da563fb9a9629c3cc360d171697b8f5`.

- `CounterType.BOUNTY` and `Counters.BOUNTY` now exist on current fork main.
- The generic named-counter LKI path was proven to already exist through the
  existing snapshot, zone-change, trigger-matcher, and `withCounter` path.
- `A8-FEATURE-002` is therefore **RESOLVED** on current fork main; no
  Chevill-specific tracker was required.
- Chevill's exact `CardDefinition` and card-specific scenario tests remain
  outstanding. Chevill is not implemented or claimed executable by this PR.
- The persisted 200-row coverage matrix, 400-row legality audit, mechanic
  inventory, and all coverage percentages remain the original pinned audit
  snapshot. No new coverage percentage is claimed without rerunning the full
  audit.

## Role annotations

The `primary_role` and `secondary_roles` labels are project-specific, manually
curated annotations for this curriculum. They are not imported from Argentum,
EDHREC, or an ML model, and they are not presented as a canonical Magic
taxonomy. EDHREC aggregates and public decklists informed archetype and role
discovery only; they did not supply these labels or get copied as decklists.

`primary_role` is exactly one deterministic bucket used for composition and
statistical summaries. It is chosen from the card's structural/main intended
purpose inside this specific deck. `secondary_roles` contains zero or more
additional functional tags that preserve meaningful multifunctionality.

For example, `Sakura-Tribe Elder` is annotated as:

```text
primary_role = CREATURE_THREAT
secondary_roles = COMBAT|RAMP
```

That convention deliberately keeps the primary bucket deterministic while
retaining the card's ramp function as a secondary tag. The taxonomy is not
redesigned in this refresh; reconsidering whether labels such as
`CREATURE_THREAT` are the best long-term names is a P3 follow-up.

## Current rules and policy verification

- Official Commander construction is 99 cards plus one commander, singleton except basic lands, with color identity determined by mana symbols across the card. The official Commander page also describes commander-tax recasting and the command-zone replacement choice. See [Commander format rules](https://magic.wizards.com/en/formats/commander).
- The official 1v1 page confirms two players, 100 cards, singleton except basic lands, and 99 + 1 construction. It does not establish a separate 1v1 ban list, so this audit applies the current Commander list. See [Commander 1v1](https://magic.wizards.com/en/formats/commander-1v1).
- The live [official banned and restricted list](https://magic.wizards.com/en/banned-restricted-list) and the [2026-02-09 Commander B&R announcement](https://magic.wizards.com/en/news/announcements/commander-banned-and-restricted-february-9-2026) were used for current legality. The 2026 announcement reports no new Commander bans and retains Jeweled Lotus as banned.
- The [2026-02-09 Brackets Beta update](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-february-9-2026) keeps Brackets beta. The [2025-10-21 Game Changers update](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025) supplies the current long-form Game Changers list used for the audit.
- Both lists target a curated Bracket-3-like experience, but Model 1.0 is stricter: zero Game Changers, zero intentional/deterministic infinite combos, zero extra-turn cards, zero mass-land-denial package, zero hard prison/stax package, zero poison package, zero alternate-win package, and no repeated universal-tutor engine.

### Legality checks

At the pinned audit snapshot, all four list variants passed the Scryfall
card-legality check used for this audit: 100 cards including commander, no
nonbasic duplicate, no illegal Commander card, and no `game_changer` flag. The
complete 400-row result is persisted in
[akiri-chevill-legality-audit.csv](akiri-chevill-legality-audit.csv); the
official sources above were the policy authority and Scryfall was the
card-data cross-check. This persisted result was not regenerated during the
PR #5 integration refresh.

## Exact decks

The recommended exact v0.1 pair is Argentum-Friendly. The Ideal pair is preserved in separate files and compared below.

### Akiri v0.1 — Argentum-Friendly

[Download the tab-separated exact list](akiri-v0.1.txt). The list contains 100 slots including commander.

| slot | primary role | secondary roles | card |
|---:|---|---|---|
| 1 | COMMANDER | SYNERGY_ENGINE | Akiri, Fearless Voyager |
| 2 | LAND | — | Plains |
| 3 | LAND | — | Plains |
| 4 | LAND | — | Plains |
| 5 | LAND | — | Plains |
| 6 | LAND | — | Plains |
| 7 | LAND | — | Plains |
| 8 | LAND | — | Plains |
| 9 | LAND | — | Plains |
| 10 | LAND | — | Plains |
| 11 | LAND | — | Plains |
| 12 | LAND | — | Plains |
| 13 | LAND | — | Plains |
| 14 | LAND | — | Plains |
| 15 | LAND | — | Plains |
| 16 | LAND | — | Mountain |
| 17 | LAND | — | Mountain |
| 18 | LAND | — | Mountain |
| 19 | LAND | — | Mountain |
| 20 | LAND | — | Mountain |
| 21 | LAND | — | Mountain |
| 22 | LAND | — | Mountain |
| 23 | LAND | — | Mountain |
| 24 | LAND | — | Command Tower |
| 25 | LAND | — | Clifftop Retreat |
| 26 | LAND | — | Battlefield Forge |
| 27 | LAND | — | Inspiring Vantage |
| 28 | LAND | — | Sacred Foundry |
| 29 | LAND | — | Plains |
| 30 | LAND | — | Mountain |
| 31 | LAND | — | Temple of Triumph |
| 32 | LAND | — | Boros Garrison |
| 33 | LAND | — | Sunhome, Fortress of the Legion |
| 34 | LAND | — | Slayers' Stronghold |
| 35 | LAND | — | Plains |
| 36 | LAND | — | Mountain |
| 37 | LAND | — | Plains |
| 38 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Sram, Senior Edificer |
| 39 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Puresteel Paladin |
| 40 | CREATURE_THREAT | COMBAT|TUTOR | Stoneforge Mystic |
| 41 | CREATURE_THREAT | COMBAT|EQUIPMENT | Ardenn, Intrepid Archaeologist |
| 42 | CREATURE_THREAT | COMBAT|EQUIPMENT | Bruenor Battlehammer |
| 43 | CREATURE_THREAT | COMBAT|EQUIPMENT | Danitha Capashen, Paragon |
| 44 | CREATURE_THREAT | COMBAT|EQUIPMENT | Armored Skyhunter |
| 45 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Wyleth, Soul of Steel |
| 46 | CREATURE_THREAT | COMBAT|EQUIPMENT | Reyav, Master Smith |
| 47 | CREATURE_THREAT | COMBAT|EQUIPMENT | Fervent Champion |
| 48 | CREATURE_THREAT | COMBAT|EQUIPMENT | Kor Blademaster |
| 49 | CREATURE_THREAT | COMBAT|EQUIPMENT | Leonin Shikari |
| 50 | CREATURE_THREAT | COMBAT|EQUIPMENT | Brass Squire |
| 51 | CREATURE_THREAT | COMBAT|EQUIPMENT | Stone Haven Outfitter |
| 52 | CREATURE_THREAT | COMBAT|EQUIPMENT | Auriok Steelshaper |
| 53 | CREATURE_THREAT | COMBAT|EQUIPMENT | Kemba, Kha Regent |
| 54 | CREATURE_THREAT | COMBAT|EQUIPMENT | Balan, Wandering Knight |
| 55 | CREATURE_THREAT | COMBAT|EQUIPMENT | Astor, Bearer of Blades |
| 56 | CREATURE_THREAT | COMBAT | Mirran Crusader |
| 57 | CREATURE_THREAT | COMBAT|PROTECTION | Selfless Spirit |
| 58 | CREATURE_THREAT | COMBAT|PROTECTION | Mother of Runes |
| 59 | CREATURE_THREAT | COMBAT|REMOVAL | Skyclave Apparition |
| 60 | CREATURE_THREAT | COMBAT|REMOVAL | Loran of the Third Path |
| 61 | CREATURE_THREAT | COMBAT|RECURSION | Sun Titan |
| 62 | EQUIPMENT | COMBAT | Bonesplitter |
| 63 | EQUIPMENT | COMBAT | Swiftfoot Boots |
| 64 | EQUIPMENT | COMBAT | Lightning Greaves |
| 65 | EQUIPMENT | COMBAT | Sword of the Animist |
| 66 | EQUIPMENT | COMBAT | Mask of Memory |
| 67 | EQUIPMENT | COMBAT | Vulshok Morningstar |
| 68 | EQUIPMENT | COMBAT | Basilisk Collar |
| 69 | EQUIPMENT | COMBAT | Fireshrieker |
| 70 | EQUIPMENT | COMBAT | Shadowspear |
| 71 | EQUIPMENT | COMBAT | Loxodon Warhammer |
| 72 | EQUIPMENT | COMBAT | Vulshok Battlegear |
| 73 | EQUIPMENT | COMBAT | Prowler's Helm |
| 74 | EQUIPMENT | COMBAT | Embercleave |
| 75 | RAMP | — | Arcane Signet |
| 76 | RAMP | — | Boros Signet |
| 77 | RAMP | — | Talisman of Conviction |
| 78 | RAMP | — | Mind Stone |
| 79 | RAMP | — | Commander's Sphere |
| 80 | RAMP | — | Wayfarer's Bauble |
| 81 | RAMP | — | Fire Diamond |
| 82 | CARD_ADVANTAGE | CARD_SELECTION | Faithless Looting |
| 83 | CARD_ADVANTAGE | CARD_SELECTION | Thrilling Discovery |
| 84 | CARD_ADVANTAGE | CARD_SELECTION | Reckless Impulse |
| 85 | CARD_ADVANTAGE | CARD_SELECTION | Mentor of the Meek |
| 86 | CARD_ADVANTAGE | CARD_SELECTION | Welcoming Vampire |
| 87 | REMOVAL | — | Swords to Plowshares |
| 88 | REMOVAL | — | Path to Exile |
| 89 | REMOVAL | — | Generous Gift |
| 90 | REMOVAL | — | Disenchant |
| 91 | REMOVAL | — | Chaos Warp |
| 92 | REMOVAL | — | Abrade |
| 93 | PROTECTION | — | Boros Charm |
| 94 | PROTECTION | — | Loran's Escape |
| 95 | BOARD_WIPE | — | Wrath of God |
| 96 | BOARD_WIPE | — | Blasphemous Act |
| 97 | RECURSION | — | Sevinne's Reclamation |
| 98 | TUTOR | — | Open the Armory |
| 99 | TUTOR | — | Steelshaper's Gift |
| 100 | CARD_ADVANTAGE | CARD_SELECTION | Outpost Siege |

Stable deck fingerprint: `d8f9fde3bf07588185df5ef143a8a6ab4f80dd3f43853d91e4131c61544f78b6`.

### Chevill v0.1 — Argentum-Friendly

[Download the tab-separated exact list](chevill-v0.1.txt). The list contains 100 slots including commander.

| slot | primary role | secondary roles | card |
|---:|---|---|---|
| 1 | COMMANDER | SYNERGY_ENGINE | Chevill, Bane of Monsters |
| 2 | LAND | — | Forest |
| 3 | LAND | — | Forest |
| 4 | LAND | — | Forest |
| 5 | LAND | — | Forest |
| 6 | LAND | — | Forest |
| 7 | LAND | — | Forest |
| 8 | LAND | — | Forest |
| 9 | LAND | — | Forest |
| 10 | LAND | — | Forest |
| 11 | LAND | — | Forest |
| 12 | LAND | — | Forest |
| 13 | LAND | — | Forest |
| 14 | LAND | — | Forest |
| 15 | LAND | — | Forest |
| 16 | LAND | — | Swamp |
| 17 | LAND | — | Swamp |
| 18 | LAND | — | Swamp |
| 19 | LAND | — | Swamp |
| 20 | LAND | — | Swamp |
| 21 | LAND | — | Swamp |
| 22 | LAND | — | Swamp |
| 23 | LAND | — | Swamp |
| 24 | LAND | — | Swamp |
| 25 | LAND | — | Swamp |
| 26 | LAND | — | Command Tower |
| 27 | LAND | — | Overgrown Tomb |
| 28 | LAND | — | Woodland Cemetery |
| 29 | LAND | — | Llanowar Wastes |
| 30 | LAND | — | Temple of Malady |
| 31 | LAND | — | Forest |
| 32 | LAND | — | Golgari Rot Farm |
| 33 | LAND | — | Swamp |
| 34 | LAND | — | Swamp |
| 35 | LAND | — | Barren Moor |
| 36 | LAND | — | War Room |
| 37 | LAND | — | Swamp |
| 38 | CREATURE_THREAT | COMBAT|REMOVAL|DEATHTOUCH | Bounty Hunter |
| 39 | CREATURE_THREAT | COMBAT|REMOVAL|DEATHTOUCH | Termination Facilitator |
| 40 | CREATURE_THREAT | COMBAT|RAMP|DEATHTOUCH | Leyline Prowler |
| 41 | CREATURE_THREAT | COMBAT|REMOVAL|DEATHTOUCH | Royal Assassin |
| 42 | CREATURE_THREAT | COMBAT|REMOVAL|DEATHTOUCH | Hooded Blightfang |
| 43 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Morbid Opportunist |
| 44 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Harvester of Souls |
| 45 | CREATURE_THREAT | COMBAT | Viscera Seer |
| 46 | CREATURE_THREAT | COMBAT|REMOVAL | Fleshbag Marauder |
| 47 | CREATURE_THREAT | COMBAT|REMOVAL | Plaguecrafter |
| 48 | CREATURE_THREAT | COMBAT|REMOVAL | Ravenous Chupacabra |
| 49 | CREATURE_THREAT | COMBAT|REMOVAL | Reclamation Sage |
| 50 | CREATURE_THREAT | COMBAT|REMOVAL|DEATHTOUCH | Acidic Slime |
| 51 | CREATURE_THREAT | COMBAT|RECURSION | Eternal Witness |
| 52 | CREATURE_THREAT | COMBAT|RAMP | Sakura-Tribe Elder |
| 53 | CREATURE_THREAT | COMBAT|RAMP | Llanowar Elves |
| 54 | CREATURE_THREAT | COMBAT|RAMP | Elvish Mystic |
| 55 | CREATURE_THREAT | COMBAT|RAMP | Wood Elves |
| 56 | CREATURE_THREAT | COMBAT | Scavenging Ooze |
| 57 | CREATURE_THREAT | COMBAT | Tireless Tracker |
| 58 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Toski, Bearer of Secrets |
| 59 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE|DEATHTOUCH | Ohran Frostfang |
| 60 | CREATURE_THREAT | COMBAT|RECURSION|DEATHTOUCH | Glissa, the Traitor |
| 61 | REMOVAL | — | Naturalize |
| 62 | CREATURE_THREAT | COMBAT|REMOVAL | Outland Liberator // Frenzied Trapbreaker |
| 63 | CREATURE_THREAT | COMBAT|CARD_ADVANTAGE | Garruk's Packleader |
| 64 | SYNERGY_ENGINE | EQUIPMENT|REMOVAL | Viridian Longbow |
| 65 | RAMP | — | Arcane Signet |
| 66 | RAMP | — | Golgari Signet |
| 67 | RAMP | — | Talisman of Resilience |
| 68 | RAMP | — | Mind Stone |
| 69 | RAMP | — | Nature's Lore |
| 70 | RAMP | — | Farseek |
| 71 | RAMP | — | Rampant Growth |
| 72 | CARD_ADVANTAGE | CARD_SELECTION | Sign in Blood |
| 73 | CARD_ADVANTAGE | CARD_SELECTION | Night's Whisper |
| 74 | CARD_ADVANTAGE | CARD_SELECTION | Read the Bones |
| 75 | CARD_ADVANTAGE | CARD_SELECTION | Phyrexian Arena |
| 76 | CARD_ADVANTAGE | CARD_SELECTION | Deathreap Ritual |
| 77 | CARD_ADVANTAGE | CARD_SELECTION | Moldervine Reclamation |
| 78 | UTILITY | — | Garruk Relentless // Garruk, the Veil-Cursed |
| 79 | CARD_ADVANTAGE | CARD_SELECTION | Plumb the Forbidden |
| 80 | CARD_ADVANTAGE | CARD_SELECTION | Guardian Project |
| 81 | CARD_ADVANTAGE | CARD_SELECTION | Garruk's Uprising |
| 82 | REMOVAL | — | Assassin's Trophy |
| 83 | REMOVAL | — | Putrefy |
| 84 | REMOVAL | — | Beast Within |
| 85 | REMOVAL | — | Abrupt Decay |
| 86 | REMOVAL | — | Go for the Throat |
| 87 | REMOVAL | — | Bite Down |
| 88 | REMOVAL | — | Tear Asunder |
| 89 | REMOVAL | — | Maelstrom Pulse |
| 90 | REMOVAL | — | Diabolic Edict |
| 91 | REMOVAL | — | Nature's Claim |
| 92 | BOARD_WIPE | — | Damnation |
| 93 | BOARD_WIPE | — | Infest |
| 94 | PROTECTION | — | Tamiyo's Safekeeping |
| 95 | PROTECTION | — | Snakeskin Veil |
| 96 | PROTECTION | — | Golgari Charm |
| 97 | PROTECTION | — | Undying Malice |
| 98 | TUTOR | — | Diabolic Intent |
| 99 | RECURSION | — | Regrowth |
| 100 | RECURSION | — | Unearth |

Stable deck fingerprint: `9651a28e762dd2927abce18950b23f55a76696750ec10ae8fbc6cbd925a8530f`.

The fingerprint is SHA-256 over UTF-8 card names in slot order, one name per line with a final newline. It is independent of role annotations and file formatting.

### Curriculum Ideal files

- [Akiri Curriculum Ideal](akiri-ideal-v0.1.txt), fingerprint `efa7182a1be4afbd6bb600fc23f37718462de0d51e88a824d7556706908813eb`.
- [Chevill Curriculum Ideal](chevill-ideal-v0.1.txt), fingerprint `3757f79dbe1ece6afd24aa4a100b8310767ccfca8433747171d688789edf6f83`.

The Ideal files are also exact 100-card lists. Their differences from the recommended pair are listed in the Ideal vs Argentum-Friendly section.

## Deck statistics

The table uses the Argentum-Friendly pair. Average mana value includes lands; nonland average is shown separately. Role counts are primary-role counts unless explicitly marked as a total. A card can have secondary roles, so columns do not sum to 99.

| statistic | Akiri | Chevill |
|---|---:|---:|
| Cards | 100 | 100 |
| Lands | 36 | 36 |
| Creatures | 27 | 26 |
| Artifacts | 21 | 5 |
| Enchantments | 1 | 5 |
| Instants | 8 | 15 |
| Sorceries | 8 | 12 |
| Planeswalkers | 0 | 1 |
| Average MV, all cards | 1.62 | 1.67 |
| Average MV, nonlands | 2.53 | 2.61 |
| Ramp | 7 | 7 |
| Card advantage | 6 | 9 |
| Targeted removal | 6 | 11 |
| Protection | 2 | 4 |
| Board wipes | 2 | 2 |
| Tutor slots, primary | 2 | 1 |
| Meaningful tutor total | 3, counting Stoneforge Mystic as a secondary Equipment tutor | 1 |
| Recursion total | 2, counting Sun Titan as a secondary recursion card | 2 |
| Interaction incl. removal, wipes, protection | 10 | 17 |
| Game Changers | 0 | 0 |
| Intentional infinite combos | 0 | 0 |

### Mana-base sanity

- Akiri has 36 lands: 27 basics and 9 nonbasics in the Friendly list. The commander requires `1RW`; early plays include one-mana equipment/creatures, two-mana Equipment tutors and ramp, and two-mana protection. The main tension is deploy/equip/protect/recast, which is intentional.
- Chevill has 36 lands: 28 basics and 8 nonbasics in the Friendly list. The commander requires `BG`; early plays include one-mana mana creatures, two-mana ramp, and one- to three-mana targeted interaction. The main tension is developing while reserving removal/protection for marked threats.
- The Friendly lists remove several utility lands with unusual opportunity costs. Command Tower and a limited set of normal dual/utility lands remain. Basics are deliberately retained to make colored-source behavior and commander-tax recasts more consistent.
- No exact hypergeometric keep-rate or colored-source percentage is claimed here. The audit is a source/pip/curve sanity check; those measurements belong in the later deterministic mulligan and paired-seed harness.

## Curriculum rationale

### Akiri package

- 26 non-commander creatures plus Akiri (27 creatures total) give the deck non-commander play, including draw engines, Equipment support, independent combat bodies, protection bodies, and a bounded recursion body.
- 13 Equipment create attachment, equip-cost, combat-keyword, and card-selection decisions without using a mass free-equip engine. Bonesplitter, Sword of the Animist, Mask of Memory, Maul/Vulshok Morningstar, Shadowspear, Fireshrieker and Embercleave cover different incentives.
- The meaningful Equipment tutor budget is three total: Stoneforge Mystic, Open the Armory, and Steelshaper's Gift. Stoneforge is deliberately counted as a secondary tutor so the primary role table does not hide the cap.
- Removal/protection uses ordinary spot interaction, two wipes, Boros Charm, Loran's Escape, and equipment-based protection. Teferi's Protection, Sunforger, and a copy/attachment subgame are excluded.
- The deck remains functional without Akiri through Sram, Puresteel, Stoneforge, Bruenor, Wyleth, and independent combat creatures. Akiri improves the deck but is not the only route to a board.

### Chevill package

- The 27-creature Ideal / 26-creature Friendly core combines bounty/deathtouch bodies, combat creatures, targeted removal bodies, sacrifice/edict pressure, recursion, and card-advantage engines.
- Ten Ideal targeted-removal slots become eleven Friendly slots after simple role substitutions; both versions also have two wipes and four protection effects. The list is interactive without becoming a 25-removal pile.
- Bounty Hunter, Termination Facilitator, Leyline Prowler, Hooded Blightfang, Royal Assassin, Glissa, Ohran Frostfang, and Viridian Longbow provide deathtouch/marked-target texture. The Longbow is kept because it creates meaningful mana/target decisions, with a later structural review.
- One tutor, Diabolic Intent, and two recursion cards keep universal search and graveyard density bounded. Fynn, Revel in Riches, Thornbite Staff, and heavy reanimation are excluded.
- Chevill is not the only plan: the list can develop mana, creatures, card engines, and removal without the commander, but Chevill materially changes target priority and resource timing.

## Removed common cards

These are deliberate exclusions, not claims that the cards are weak.

- **Akiri:** Teferi's Protection (official Game Changer and too much protection for Model 1.0); Sunforger (toolbox/cost decision explosion); Colossus Hammer (Voltron all-in); Helm of the Host and Godo, Bandit Warlord (deterministic combo risk); Ancient Tomb, Smothering Tithe, Jeska's Will, and other high-impact Game Changers; and additional universal Equipment tutors beyond the cap.
- **Chevill:** Demonic Tutor, Vampiric Tutor, and other repeated universal tutors; Fynn, the Fangbearer (poison package); Revel in Riches (alternate win); Thornbite Staff (pinger/untap lock-combo surface); Grave Pact/Dictate-style sacrifice locks; and a heavy reanimation package. Politics-first cards from multiplayer lists were also not selected for 1v1 semantics.

EDHREC was used for archetype and role discovery, not as a decklist. The current [Akiri Equipment aggregates](https://edhrec.com/commanders/akiri-fearless-voyager/equipment), [Chevill aggregate](https://edhrec.com/commanders/chevill-bane-of-monsters), and public [Akiri](https://archidekt.com/decks/23520591/akiri_fearless_voyager_equipment) / [Chevill](https://archidekt.com/decks/23891924/bane_of_monsters) lists informed role choices; neither public list was copied.

## Combo audit

The audit used [Commander Spellbook](https://commanderspellbook.com/) lookups plus independent static inspection of all four lists. The Spellbook examples below are exclusion checks, not claims that the selected decks contain them; this is not a complete combo solver or gameplay proof.

| combo_id / description | cards involved | present in Akiri? | present in Chevill? | severity | action |
|---|---|---|---|---|---|
| COMBO-001 Godo + Helm of the Host | Godo, Bandit Warlord; Helm of the Host | No | No | HIGH if complete | SAFE — both omitted; see [Spellbook](https://commanderspellbook.com/combo/1692-4251/) |
| COMBO-002 deathtouch Longbow untap line | Thornbite Staff; Vorpal Sword; Viridian Longbow | No | Longbow only | HIGH if complete | NOT_A_COMBO — only Longbow is selected; see [Spellbook](https://commanderspellbook.com/combo/85-1284-2178/) |
| COMBO-003 sacrifice/recursion drain loop | typical Blood Artist/Pitiless Plunderer/Mikaeus-style package | No | No | HIGH if complete | SAFE — required components absent |
| COMBO-004 Sun Titan + Sevinne's Reclamation recursion | Sun Titan; Sevinne's Reclamation plus a loop outlet | Both cards in Ideal Akiri only | No | MEDIUM | SAFE — no outlet/closed loop selected; manual review if future recursion changes |
| COMBO-005 alternate win / poison / deterministic tutor kill | Revel in Riches; Fynn; repeated universal tutor chain | No | No | HIGH | SAFE — policy packages excluded |

Final result: `INTENTIONAL_INFINITE_COMBOS = 0`; `ACCIDENTAL_DETERMINISTIC_COMBOS = 0` identified; `extra_turn_cards = 0`; `mass_land_denial_package = 0`; `hard_prison_package = 0`; `poison_package = 0`; `alternate_win_package = 0`. `Viridian Longbow` remains a structural review item, not a combo finding.

## Argentum coverage method

- The live [Argentum set-completion tracker](https://magic.wingedsheep.com/set-completion) was treated as discovery evidence only.
- Current source was indexed at audit SHA using exact-name definition lookup, `CardDiscovery` semantics, `CardRegistry` semantics, generated-definition markers, and exact `*ScenarioTest.kt` filename discovery.
- `HAND_AUTHORED`, `GENERATED`, and `UNKNOWN` are source-index classifications. A generated definition is not treated as conformance proof.
- The source-level `CardDiscoveryTest` gate could not be run in this environment because the repository's `just` command was unavailable. Therefore the result is intentionally conservative and `ARGENTUM_COVERAGE` remains PARTIAL; no green build/test claim is made.
- The strongest source evidence used in this snapshot is an exact source definition plus relevant scenario/conformance evidence. A definition without such evidence is `SUPPORTED_DEFINITION_TEST_UNCLEAR`.

## Coverage summary (AUDIT SNAPSHOT)

The CSV is the recommended Friendly 200-slot matrix: [akiri-chevill-coverage.csv](akiri-chevill-coverage.csv). It contains exactly 100 Akiri rows and 100 Chevill rows, including commander and repeated basic-land slots.

The percentages and counts below remain pinned to the original ARG-DECK-01
audit source SHA. PR #5 changed current implementation status for
`A8-FEATURE-002`, but this refresh does not claim new coverage percentages.

### Argentum-Friendly

| primary coverage class | Akiri | Chevill |
|---|---:|---:|
| SUPPORTED_AND_TESTED | 3 | 5 |
| SUPPORTED_DEFINITION_TEST_UNCLEAR | 62 | 64 |
| CARD_DEFINITION_MISSING | 35 | 30 |
| REUSABLE_MECHANIC_MISSING | 0 | 1 |
| CORE_RULE_GAP | 0 | 0 |
| COVERAGE_UNCERTAIN | 0 | 0 |

| burden metric | Akiri | Chevill |
|---|---:|---:|
| supported and tested | 3 | 5 |
| supported definition, test unclear | 62 | 64 |
| supported total | 65/100 (65%) | 69/100 (69%) |
| unique missing exact cards | 35 | 30 |
| missing-card mechanic labels touched | 42 | 47 |
| unique generated definitions | 20 | 17 |
| exact scenario-test filenames found | 3 | 7 |
| card-closure burden | MEDIUM | MEDIUM-LARGE because Chevill also needs a reusable predicate |

### Curriculum Ideal

| primary coverage class | Akiri | Chevill |
|---|---:|---:|
| SUPPORTED_AND_TESTED | 3 | 2 |
| SUPPORTED_DEFINITION_TEST_UNCLEAR | 54 | 56 |
| CARD_DEFINITION_MISSING | 43 | 41 |
| REUSABLE_MECHANIC_MISSING | 0 | 1 |
| CORE_RULE_GAP | 0 | 0 |
| COVERAGE_UNCERTAIN | 0 | 0 |

| burden metric | Akiri Ideal | Chevill Ideal |
|---|---:|---:|
| supported total | 57/100 (57%) | 58/100 (58%) |
| unique missing exact cards | 43 | 41 |
| missing-card mechanic labels touched | 45 | 55 |
| unique generated definitions | 17 | 16 |
| card-closure burden | MEDIUM-LARGE | MEDIUM-LARGE |

Coverage percentages are definition/test evidence percentages, not probabilities of a card working and not expected game win rates.

### Exact missing cards — recommended Friendly pair

- **Akiri (35):** Akiri, Fearless Voyager; Sram, Senior Edificer; Puresteel Paladin; Stoneforge Mystic; Ardenn, Intrepid Archaeologist; Bruenor Battlehammer; Armored Skyhunter; Reyav, Master Smith; Fervent Champion; Kor Blademaster; Leonin Shikari; Brass Squire; Stone Haven Outfitter; Kemba, Kha Regent; Balan, Wandering Knight; Astor, Bearer of Blades; Mirran Crusader; Selfless Spirit; Mother of Runes; Skyclave Apparition; Loran of the Third Path; Sun Titan; Sword of the Animist; Shadowspear; Prowler's Helm; Embercleave; Talisman of Conviction; Commander's Sphere; Thrilling Discovery; Swords to Plowshares; Path to Exile; Generous Gift; Loran's Escape; Sevinne's Reclamation; Steelshaper's Gift
- **Chevill (30):** War Room; Bounty Hunter; Termination Facilitator; Leyline Prowler; Hooded Blightfang; Viscera Seer; Fleshbag Marauder; Plaguecrafter; Ravenous Chupacabra; Acidic Slime; Elvish Mystic; Toski, Bearer of Secrets; Ohran Frostfang; Glissa, the Traitor; Outland Liberator // Frenzied Trapbreaker; Talisman of Resilience; Sign in Blood; Read the Bones; Deathreap Ritual; Moldervine Reclamation; Plumb the Forbidden; Guardian Project; Assassin's Trophy; Abrupt Decay; Go for the Throat; Tear Asunder; Nature's Claim; Damnation; Tamiyo's Safekeeping; Golgari Charm

Chevill itself is not in the missing-card count because its primary classification is `REUSABLE_MECHANIC_MISSING`; its exact CardDefinition is also absent.

### Exact missing cards — Curriculum Ideal pair

- **Akiri Ideal (43):** Akiri, Fearless Voyager; Spectator Seating; Rugged Prairie; Buried Ruin; War Room; Bonders' Enclave; Sram, Senior Edificer; Puresteel Paladin; Stoneforge Mystic; Ardenn, Intrepid Archaeologist; Bruenor Battlehammer; Armored Skyhunter; Reyav, Master Smith; Fervent Champion; Kor Blademaster; Leonin Shikari; Brass Squire; Stone Haven Outfitter; Kemba, Kha Regent; Balan, Wandering Knight; Astor, Bearer of Blades; Mirran Crusader; Selfless Spirit; Mother of Runes; Skyclave Apparition; Loran of the Third Path; Sun Titan; Sword of the Animist; Maul of the Skyclaves; Shadowspear; O-Naginata; Prowler's Helm; Embercleave; Talisman of Conviction; Commander's Sphere; Thrilling Discovery; Swords to Plowshares; Path to Exile; Generous Gift; Wear // Tear; Loran's Escape; Sevinne's Reclamation; Steelshaper's Gift
- **Chevill Ideal (41):** Tainted Wood; Nurturing Peatland; Bojuka Bog; War Room; Takenuma, Abandoned Mire; Bounty Hunter; Termination Facilitator; Leyline Prowler; Hooded Blightfang; Viscera Seer; Fleshbag Marauder; Plaguecrafter; Ravenous Chupacabra; Acidic Slime; Elvish Mystic; Toski, Bearer of Secrets; Ohran Frostfang; Glissa, the Traitor; Cankerbloom; Outland Liberator // Frenzied Trapbreaker; Beast Whisperer; Talisman of Resilience; Sign in Blood; Read the Bones; Deathreap Ritual; Moldervine Reclamation; Vraska, Golgari Queen; Plumb the Forbidden; Guardian Project; Assassin's Trophy; Abrupt Decay; Go for the Throat; Tear Asunder; Sheoldred's Edict; Nature's Claim; Damnation; Languish; Tamiyo's Safekeeping; Golgari Charm; Malakir Rebirth // Malakir Mire; Victimize

### Generated-but-unverified definition names in Friendly matrix

- **Akiri (20 unique):** Abrade; Bonesplitter; Boros Charm; Boros Signet; Disenchant; Faithless Looting; Fire Diamond; Fireshrieker; Lightning Greaves; Loxodon Warhammer; Open the Armory; Slayers' Stronghold; Sunhome, Fortress of the Legion; Swiftfoot Boots; Temple of Triumph; Vulshok Battlegear; Vulshok Morningstar; Wayfarer's Bauble; Wrath of God; Wyleth, Soul of Steel
- **Chevill (17 unique):** Bite Down; Diabolic Intent; Eternal Witness; Farseek; Golgari Signet; Nature's Lore; Night's Whisper; Phyrexian Arena; Putrefy; Rampant Growth; Reclamation Sage; Regrowth; Royal Assassin; Sakura-Tribe Elder; Temple of Malady; Unearth; Wood Elves

## 200-row matrix

Recommended matrix: [akiri-chevill-coverage.csv](akiri-chevill-coverage.csv).

Required columns are present: `deck`, `slot_number`, `card_name`, `oracle_id`, `primary_role`, `secondary_roles`, `mana_value`, `color_identity`, `card_type`, `source_reference`, `argentum_definition_found`, `argentum_definition_path`, `definition_kind`, `scenario_test_found`, `scenario_test_path`, `coverage_class`, `mechanic_families`, `decision_families`, `game_changer`, `banned`, `combo_member`, `tutor`, `board_wipe`, `engine_notes`, and `replacement_candidate`.

The `replacement_candidate` field preserves the Ideal card at each Friendly substitution slot, so the burden reduction remains auditable rather than silently overwriting the curriculum choice.

## Commander-specific findings (AUDIT SNAPSHOT)

### Akiri, Fearless Voyager

- Exact current CardDefinition: **not found**; primary class `CARD_DEFINITION_MISSING`.
- Relevant generic source vocabulary found: equipped-creature filtering, Equipment attachment/equip, attack-with-filter batching for “one or more”, draw, unattach/cost vocabulary, temporary abilities, and indestructible.
- Not proven by current selected-card evidence: the complete `equipped creatures attack -> draw one card` path, one-or-more cardinality, unattach as cost, temporary indestructible duration, and end-to-end commander identity/recast scenario.
- The difficulty is currently an exact card-definition/test gap, not evidence of a missing reusable mechanic. Commander-zone conformance remains the shared foundational blocker and is tracked separately.

### Chevill, Bane of Monsters

- Exact current CardDefinition: **not found**; primary class `REUSABLE_MECHANIC_MISSING` because the exact named bounty-counter/LKI condition is not demonstrated by the reusable SDK.
- Generic source support exists for counters, `TriggerContext.lastKnownCounters`, ZoneChangeEvent last-known fields, target predicates, opponent control, dies triggers, draw, and life gain.
- Missing reusable proof: “this opponent-controlled permanent has no bounty counter” at upkeep, place the named bounty counter, retain the mark through control/zone changes, and trigger only when the marked permanent dies. Current generic `TriggeringEntityHadCounters` is not sufficient because it does not prove the named bounty-counter condition.
- Required edge-case scenarios: existing bounty; no legal target; target changes controller; simultaneous death; Chevill leaves; marked permanent LKI; draw/life gain; commander recast.

### Chevill post-audit status

The reusable named-counter/LKI blocker described above was resolved after this
audit by ARG-DECK-02 / PR #5. The generic capability already existed, and PR #5
added the missing `BOUNTY` vocabulary plus independent LKI characterization and
serialization tests. No source-relative marked-permanent tracker was needed.
ARG-DECK-03 now adds Chevill's exact `CardDefinition`, generic upkeep targeting,
bounty placement, draw/life effect, and focused card-specific scenario tests.
The current commander slot is classified `SUPPORTED_AND_TESTED`; commander-zone
conformance remains a separate ARG-02.1 workstream. The surrounding counts and
snapshot findings in this document remain historical and are intentionally not
regenerated by ARG-DECK-03.

## Mechanic inventory

See [akiri-chevill-mechanics.md](akiri-chevill-mechanics.md). It contains the
deduplicated normalized inventory, the cards using each family, source support
assessment, exact scenario-test paths, and LOW/MEDIUM/HIGH/BLOCKER risk. That
inventory is also pinned to the historical audit snapshot; its post-audit
status note records the PR #5 resolution.

The historical highest-risk reusable surfaces were: commander zone movement;
named bounty counter plus marked-permanent LKI; equipment
attachment/unattach/duration; DFC/transform; and the Longbow/deathtouch
structural package. On current fork main, the named-counter/LKI item is
resolved; commander-zone conformance and the remaining card-definition/test
work are still open.

## Decision-family inventory

Counts below are static potential card-slot surfaces from the recommended matrix, not runtime branch counts or measured combinatorial complexity.

### Akiri

| decision family | potential card-slot count |
|---|---:|
| PRIORITY_ACTION | 63 |
| TARGET | 25 |
| MODE | 2 |
| X_VALUE | 0 |
| PAYMENT | 35 |
| MANA_SOURCE_SELECTION | 43 |
| CARD_SELECTION | 15 |
| ATTACK | 25 |
| BLOCK | 24 |
| DAMAGE_ASSIGNMENT | 24 |
| CONFIRMATION | 3 |
| TRIGGER_ORDER | 0 |
| REPLACEMENT_CHOICE | 1 |
| SEARCH | 4 |
| ORDER | 0 |
| DISTRIBUTE | 0 |
| OTHER_STRUCTURED_DECISION | 0 |

### Chevill

| decision family | potential card-slot count |
|---|---:|
| PRIORITY_ACTION | 62 |
| TARGET | 29 |
| MODE | 4 |
| X_VALUE | 0 |
| PAYMENT | 32 |
| MANA_SOURCE_SELECTION | 43 |
| CARD_SELECTION | 20 |
| ATTACK | 26 |
| BLOCK | 25 |
| DAMAGE_ASSIGNMENT | 26 |
| CONFIRMATION | 3 |
| TRIGGER_ORDER | 0 |
| REPLACEMENT_CHOICE | 1 |
| SEARCH | 6 |
| ORDER | 0 |
| DISTRIBUTE | 0 |
| OTHER_STRUCTURED_DECISION | 0 |

The pair covers priority, target, payment, mana-source, card-selection, attack, block, damage-assignment, search, confirmation, modal, and replacement-choice surfaces. `X_VALUE`, `ORDER`, `DISTRIBUTE`, and `OTHER_STRUCTURED_DECISION` are zero in these exact lists. `TRIGGER_ORDER` is zero as an intrinsic single-card count; later game-state traces can still create trigger-order decisions when multiple triggers coexist.

## Ideal vs Argentum-Friendly

| metric | Akiri Ideal | Akiri Friendly | Chevill Ideal | Chevill Friendly |
|---|---:|---:|---:|---:|
| definition-supported total | 57% | 65% | 58% | 69% |
| unique missing cards | 43 | 35 | 41 | 30 |
| missing-card mechanic labels touched | 45 | 42 | 55 | 47 |
| recommended closure burden | MEDIUM-LARGE | MEDIUM | MEDIUM-LARGE | MEDIUM-LARGE |

### Akiri substitutions

- Spectator Seating -> Plains; Rugged Prairie -> Mountain; Buried Ruin -> Plains; War Room -> Mountain; Bonders' Enclave -> Plains.
- Maul of the Skyclaves -> Vulshok Morningstar; O-Naginata -> Vulshok Battlegear; Wear // Tear -> Disenchant.
- Effect: +8 percentage points of definition-supported slots, simpler mana/utility behavior, and no loss of the Akiri commander/equipment/tutor/combat core. The curriculum loss is LOW: utility-land texture, one split-card mode, and two Equipment patterns are reduced.

### Chevill substitutions

- Tainted Wood -> Forest; Nurturing Peatland -> Swamp; Bojuka Bog -> Swamp; Takenuma, Abandoned Mire -> Swamp.
- Cankerbloom -> Naturalize; Beast Whisperer -> Garruk's Packleader; Vraska, Golgari Queen -> Garruk Relentless // Garruk, the Veil-Cursed; Sheoldred's Edict -> Diabolic Edict; Languish -> Infest; Malakir Rebirth // Malakir Mire -> Undying Malice; Victimize -> Unearth.
- Effect: +11 percentage points of definition-supported slots, simpler land and split/DFC/planeswalker/recursion surfaces, while preserving bounty/deathtouch, target/edict, artifact/enchantment interaction, protection, recursion, and board-wipe roles. The curriculum loss is LOW-MEDIUM; the Friendly list has less variation in utility lands and some card-advantage/interaction texture.

Recommended version: **Argentum-Friendly** for the first implementation/validation pass, with the **Curriculum Ideal** lists retained as the reference for deciding whether a missing card is worth implementing. The Friendly version does not remove the commander, core equipment/bounty decisions, combat, or target-selection depth.

## Structural matchup risks

All findings in this section are `STRUCTURAL_INFERENCE_ONLY`; no win rate, simulation, or engine-AI balance claim is made.

- **P1 — Longbow/deathtouch removal pressure:** Viridian Longbow plus six deathtouch-marked bodies can turn spare mana into repetitive creature control. It is not a complete Spellbook combo or hard lock in these lists, but it can reduce combat learning if it dominates. Later paired-seed traces should measure frequency, not assume a ban.
- **P2 — Akiri protection/equipment snowball:** Akiri has a proactive attachment engine, equipment-based protection, and attack/card-advantage pressure. A first untap with an equipped threat may create a snowball; Chevill’s artifact interaction, edicts, and timing of removal must remain meaningful.
- **P3 — Initiative and resource asymmetry:** Chevill has more targeted interaction and ongoing card engines; Akiri has more immediate combat pressure and attachment decisions. Chevill’s hold-up requirements can magnify first-player advantage, while Chevill recursion/card advantage can lengthen games.

| requested risk | assessment |
|---|---|
| Akiri removal resilience | moderate: Boros Charm, Loran's Escape, Boots/Greaves, Mother of Runes, Selfless Spirit; exact coverage is uneven |
| Chevill artifact-removal density | moderate: Reclamation Sage, Acidic Slime, Naturalize/Cankerbloom/Outland role; not every exact definition exists |
| Akiri protection density | moderate, intentionally bounded: two primary protection cards plus equipment/creature support |
| Chevill deathtouch density | high enough to matter; Longbow is the P1 review item |
| board-wipe asymmetry | low: both lists have two wipes and neither has a deliberately asymmetric wipe package |
| card-advantage asymmetry | Chevill higher sustained density; Akiri’s advantage is more attack/equipment dependent |
| mana-curve asymmetry | small: Friendly all-card MV 1.62 vs 1.67; Chevill has more instant/interaction hold-up |
| commander dependency | moderate for both; support packages function without commander, but each commander is a strategic engine |
| first-player advantage | medium risk: Akiri is proactive; Chevill’s target/hold-up timing may punish the draw |
| game-length risk | medium: Chevill recursion/card engines and Longbow can lengthen or stall games |
| Voltron snowball risk | medium-low: Equipment is broad and combat-based, with no Hammer all-in |
| removal-lock risk | medium: possible repetitive Longbow/deathtouch interaction, not a hard lock by list inspection |

## Proposed A8 closure backlog

See [akiri-chevill-closure-backlog.md](akiri-chevill-closure-backlog.md). Ordered items are:

1. A8-FEATURE-001 commander zone movement conformance (shared blocker).
2. A8-CARD-001 Akiri exact definition and scenario.
3. A8-CARD-002 Chevill exact definition and scenario; A8-FEATURE-002 is
   resolved by PR #5.
4. A8-CARD-003 Akiri equipment support batch.
5. A8-CARD-004 Chevill bounty/deathtouch support batch.
6. A8-CARD-005 remaining exact definitions/tests.
7. A8-FEATURE-003 Longbow/deathtouch structural review after paired-seed smoke evidence.

## Artifacts

- [akiri-v0.1.txt](akiri-v0.1.txt) — recommended Friendly exact list.
- [chevill-v0.1.txt](chevill-v0.1.txt) — recommended Friendly exact list.
- [akiri-ideal-v0.1.txt](akiri-ideal-v0.1.txt) — Curriculum Ideal exact list.
- [chevill-ideal-v0.1.txt](chevill-ideal-v0.1.txt) — Curriculum Ideal exact list.
- [akiri-chevill-coverage.csv](akiri-chevill-coverage.csv) — exactly 200 deck-slot rows.
- [akiri-chevill-legality-audit.csv](akiri-chevill-legality-audit.csv) — persisted 400-row legality/Game Changer/color-identity audit for all four variants.
- [akiri-chevill-legality-audit.md](akiri-chevill-legality-audit.md) — legality-audit method and source snapshot.
- [akiri-chevill-mechanics.md](akiri-chevill-mechanics.md) — deduplicated mechanic inventory.
- [akiri-chevill-closure-backlog.md](akiri-chevill-closure-backlog.md) — remaining A8 work; A8-FEATURE-002 is resolved, and card work remains unimplemented.

## Recommendation

Keep Akiri/Chevill as the first fixed Commander matchup. The exact-list review confirms the desired mixture of combat, removal, protection, target selection, mana reservation, commander recast pressure, hidden-information choices, and asymmetric but fair plans. The remaining problems are implementation/validation gates, not evidence that the matchup itself is substantially worse than the prior curriculum research suggested.

Carry forward the Friendly pair for first engine closure, retain the Ideal pair as the quality bar, and do not claim balance until A2 is green and deterministic paired-seed validation exists.

## PR handoff

| field | value |
|---|---|
| branch | `agent/deck-01-akiri-chevill-coverage` |
| commit | recorded in the publication handoff after verification |
| target repository | `chrismaghuhn/argentum-engine` |
| target branch | `main` |
| draft | yes |
| upstream PR | none |

Suggested commit subject: `docs: define Akiri Chevill curriculum and coverage`.
