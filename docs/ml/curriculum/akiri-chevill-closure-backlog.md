# ARG-DECK-01 — proposed A8 closure backlog

Audit basis:

- Audit source commit: `31853cfe91b52718dc3fb67f159e6267d9c5fcc1`
- Pinned upstream SHA: `d66a5d7f1b46b0ed8891c34ccfe163d491c4ff3d`
- Scope: documentation and data analysis only. No backlog item is implemented here.

The order is severity first, then the number of cards unblocked, then curriculum importance.

## A8-FEATURE-001 — Commander zone movement conformance

Severity: BLOCKER. Shared unblock: both commanders and any future Commander recast tests.

Scope:

- Complete the distinction between the commander replacement choices corresponding to moving the card to the command zone from a graveyard or exile versus the library or hand.
- Preserve the pending zone-change/replacement decision through the continuation boundary.
- Add conformance evidence for commander removal, death, exile, and repeated recast paths.
- Keep this work aligned with ARG-02.1; this audit does not modify the active A2 branch or its production code.

Why first: both exact lists use Commander casting/recasting as a curriculum feature, so a false “supported” label here would contaminate later game traces.

## A8-FEATURE-002 — Named counter plus marked-permanent LKI predicate

Severity: BLOCKER for Chevill. Shared future value: Chevill plus any card that places a named marker/counter and reacts after the marked permanent leaves.

Required reusable shape:

- test a specific counter type, not merely “had any counter”;
- retain last-known counters and controller/target identity through a zone change;
- distinguish a permanent that was marked from a permanent that merely has a current counter;
- handle control change, no marked permanent, simultaneous deaths, and the draw/life trigger ordering;
- fail closed when the named counter or LKI evidence is unavailable.

This should be a reusable condition/trigger vocabulary, not a Chevill-only callback.

## A8-CARD-001 — Akiri, Fearless Voyager

Severity: HIGH. Curriculum importance: commander.

Needed definition and scenario coverage:

- equipped-creature attack trigger with “one or more” cardinality;
- draw choice;
- Equipment attachment state;
- unattach as a cost;
- temporary indestructible until end of turn;
- commander identity and repeated commander removal/recast once A8-FEATURE-001 is green.

The current SDK appears to contain relevant generic attack-batch, equipment, draw, cost, and temporary-ability vocabulary, so this is presently classified as a missing exact card definition rather than a new mechanic by itself.

## A8-CARD-002 — Chevill, Bane of Monsters

Severity: HIGH. Curriculum importance: commander.

Depends on A8-FEATURE-002 and A8-FEATURE-001.

Scenario matrix:

- upkeep with a legal opponent-controlled permanent;
- no legal target;
- target already carrying a bounty counter;
- target changes controller;
- marked target dies and is observed through LKI;
- simultaneous deaths;
- draw and life-gain resolution;
- Chevill leaves before the trigger resolves;
- commander removal/recast.

## A8-CARD-003 — Akiri equipment support batch

Group by shared equipment/equip/attachment vocabulary:

- Sram, Senior Edificer
- Puresteel Paladin
- Stoneforge Mystic
- Ardenn, Intrepid Archaeologist
- Bruenor Battlehammer
- Armored Skyhunter
- Reyav, Master Smith
- Fervent Champion
- Kor Blademaster
- Leonin Shikari
- Brass Squire
- Stone Haven Outfitter
- Kemba, Kha Regent
- Balan, Wandering Knight
- Astor, Bearer of Blades
- Sword of the Animist
- Shadowspear
- Prowler's Helm
- Embercleave

Do not collapse these into one shared test file: each implemented card still needs its own scenario file. The grouping is only an implementation-order hint.

## A8-CARD-004 — Chevill bounty/deathtouch support batch

Group by reusable counter, deathtouch, marked-target, death, and target-selection vocabulary:

- Bounty Hunter
- Termination Facilitator
- Leyline Prowler
- Hooded Blightfang
- Viscera Seer
- Fleshbag Marauder
- Plaguecrafter
- Ravenous Chupacabra
- Acidic Slime
- Elvish Mystic
- Toski, Bearer of Secrets
- Ohran Frostfang
- Glissa, the Traitor
- Outland Liberator // Frenzied Trapbreaker
- Talisman of Resilience
- Deathreap Ritual
- Moldervine Reclamation
- Plumb the Forbidden
- Guardian Project
- Assassin's Trophy
- Abrupt Decay
- Go for the Throat
- Tear Asunder
- Nature's Claim
- Damnation
- Tamiyo's Safekeeping
- Golgari Charm

Again, group for vocabulary reuse but keep one scenario file per card.

## A8-CARD-005 — Remaining exact definitions and scenarios

Akiri remaining Ideal-only definitions:

- Spectator Seating
- Rugged Prairie
- Buried Ruin
- War Room
- Bonders' Enclave
- Danitha Capashen, Paragon
- Wyleth, Soul of Steel
- Auriok Steelshaper
- Loxodon Warhammer
- Basilisk Collar
- Fireshrieker
- Talisman of Conviction
- Mind Stone
- Commander's Sphere
- Fire Diamond
- Faithless Looting
- Thrilling Discovery
- Mentor of the Meek
- Outpost Siege
- Swords to Plowshares
- Path to Exile
- Generous Gift
- Chaos Warp
- Abrade
- Wear // Tear
- Boros Charm
- Loran's Escape
- Wrath of God
- Blasphemous Act
- Sevinne's Reclamation
- Open the Armory
- Steelshaper's Gift

Friendly substitutions reduce this list by using Plains/Mountain, Vulshok Morningstar, Vulshok Battlegear, and Disenchant where the role remains coherent.

Chevill remaining Ideal-only definitions:

- Tainted Wood
- Nurturing Peatland
- Bojuka Bog
- Takenuma, Abandoned Mire
- Royal Assassin
- Morbid Opportunist
- Harvester of Souls
- Sakura-Tribe Elder
- Llanowar Elves
- Scavenging Ooze
- Tireless Tracker
- Reclamation Sage
- Eternal Witness
- Beast Whisperer
- Sign in Blood
- Night's Whisper
- Read the Bones
- Phyrexian Arena
- Deathreap Ritual
- Vraska, Golgari Queen
- Plumb the Forbidden
- Guardian Project
- Regrowth
- Victimize
- Putrefy
- Beast Within
- Bite Down
- Maelstrom Pulse
- Sheoldred's Edict
- Languish
- Malakir Rebirth // Malakir Mire
- Infest
- Unearth
- Garruk Relentless // Garruk, the Veil-Cursed
- Diabolic Edict
- Naturalize
- Garruk's Packleader

The Friendly list retains exact source-backed or simpler role substitutes for the last group where practical.

## A8-FEATURE-003 — Viridian Longbow structural review

Severity: MEDIUM, not currently a required engine feature.

Run a later paired-seed review of:

- Longbow plus deathtouch bodies;
- mana reservation against Chevill’s upkeep trigger and removal;
- whether the package creates repetitive board denial;
- whether a curriculum restriction is preferable to an engine restriction.

Do not label the package an infinite combo: the selected lists omit Thornbite Staff, Vorpal Sword, and an untap loop.

## Exit criteria

A8 closure is ready for paired-seed environment validation only after:

1. A2 commander-zone evidence is green.
2. Chevill’s named-counter/LKI condition has reusable tests.
3. Both commanders have card-specific scenario tests.
4. Coverage counts have been regenerated from the then-current pinned SHA.
5. Structural matchup risks have been checked with a deterministic, paired-seed harness.

No item above authorizes card or mechanic implementation in ARG-DECK-01.
