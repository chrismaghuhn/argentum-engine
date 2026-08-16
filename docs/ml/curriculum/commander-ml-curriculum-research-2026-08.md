# Commander ML Curriculum Research — August 2026

**Research status:** verified against the live official Commander pages and publicly inspectable resources on **15 August 2026**.

All numeric deck scores, matchup-balance judgments, expected episode lengths, and implementation-burden estimates below are **ML/engineering inferences**, not measured win rates. Rules, bracket policy, deck contents, known combos, and Argentum repository findings are cited separately.

---

## Executive recommendation

| DecisionRecommendation                    |                                                                                                                                                                                 |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Recommended initial bracket**           | **Curated Bracket 3 vs Bracket 3**                                                                                                                                              |
| **Recommended curriculum shape**          | Optional Bracket 2 conformance stage → fixed Bracket 3 matchup → multi-deck Bracket 3 generalization → selective Bracket 4 → multiplayer and cEDH as separate advanced branches |
| **Recommended provisional first matchup** | **Akiri, Fearless Voyager vs Chevill, Bane of Monsters**                                                                                                                        |
| **Target profile**                        | Boros Equipment Midrange vs Golgari Bounty/Deathtouch Interactive Midrange                                                                                                      |
| **Game Changers**                         | Initially **0 per deck**, despite Bracket 3 allowing up to three                                                                                                                |
| **Intentional infinite combos**           | **0**                                                                                                                                                                           |
| **Meaningful tutors**                     | Approximately **2–3 for Akiri, 1–2 for Chevill**                                                                                                                                |
| **Expected episode length**               | **Medium to long**, requiring empirical calibration                                                                                                                             |
| **Confidence**                            | High that it is a strong curriculum candidate; medium that it will be balanced before list tuning                                                                               |

### Why Bracket 3

Bracket 2 is valuable for engine debugging, but its official intent emphasizes unoptimized, low-pressure, straightforward play. Bracket 3 adds stronger synergy, higher card quality, effective disruption, and a larger mixture of proactive and reactive decisions while still excluding the explosive consistency expected in Bracket 4 and the metagame-optimized combo environment of Bracket 5. That is the best region for producing a clear skill gradient without immediately turning the task into tutor inference and stack-based combo defense. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

### Why Akiri versus Chevill

The pairing repeatedly exercises a compact set of strategically reusable primitives:

- threat deployment;
- equipment allocation;
- attack and block selection;
- removal timing;
- protection timing;
- target selection;
- mana reservation;
- commander casting and recasting;
- commander tax;
- command-zone replacement choices;
- hidden-information reasoning around removal and protection.

Neither commander is purely ornamental, but neither deck should completely stop functioning when its commander is removed. There is meaningful asymmetry—Equipment combat versus removal/deathtouch attrition—without requiring radically different rule systems or deterministic combo execution.

The major risk is structural: Chevill may naturally prey on an Equipment deck through removal and deathtouch. That must be controlled through exact deck construction and then tested with paired seeds, starting-player swaps, and several baseline policies. No credible public 1v1 Akiri–Chevill matchup matrix was located, so no win-rate claim is justified.

### Important format qualification

Official Commander is presented by Wizards as a multiplayer format, with the current format page listing three to five players and describing four-player Commander. A two-player environment retaining Commander construction, 40 life, commander tax, command-zone rules, and commander damage is therefore a **custom research adaptation**, not the ordinary official play environment. The project must explicitly freeze its 1v1 mulligan, starting-player draw, seating, and concession policies rather than assuming the multiplayer defaults answer those questions. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/formats/commander "https://magic.wizards.com/en/formats/commander"))

---

# Current Commander bracket framework

## Current status as of 15 August 2026

The live Wizards Commander page still describes Commander Brackets as **optional** and **currently in beta**. The most recent Commander-specific bracket update I located was published on **9 February 2026**; the live format and banned-list pages were also checked on 15 August 2026. I found no later official Commander-specific bracket redefinition through that date. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/formats/commander "https://magic.wizards.com/en/formats/commander"))

A bracket belongs to the **exact 100-card deck and its intent**, not inherently to the commander. Akiri, for example, can lead a Bracket 2 deck, a Bracket 3 deck, or an optimized Bracket 4 deck depending on tutors, fast mana, protection, combos, interaction, and expected game speed.

## Bracket 1: Exhibition

**Official intent:** theme, idea, or presentation over power; highly thematic or deliberately suboptimal win conditions; generally at least nine turns to showcase the deck. Rule Zero flexibility is especially central here. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**ML implication:** usually a poor optimization target. Card selection may be deliberately inconsistent, reward signals become noisy, and the deck may contain strange one-off mechanics solely for theme.

## Bracket 2: Core

**Official intent:** unoptimized and straightforward decks, incremental and telegraphed win conditions, low-pressure proactive play, and generally at least eight turns. Bracket 2 is no longer defined as synonymous with preconstructed decks. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**ML implication:** excellent for a conformance and action-protocol stage. It may be too forgiving for the first serious strategic-learning target because weak sequencing is not always punished quickly or consistently.

## Bracket 3: Upgraded

**Official intent:** strong synergy, high card quality, effective disruption, many proactive and reactive plays, and sufficient accumulated resources to create game-ending turns. Players should generally receive at least six turns before winning or losing. Bracket 3 may use up to three Game Changers but does not need any. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**ML implication:** the best first serious target. It supplies interaction, sequencing, hidden information, combat, resource planning, and commander decisions without making compact deterministic wins the primary task.

## Bracket 4: Optimized

**Official intent:** fast, lethal, consistent, explosive decks outside the dedicated cEDH metagame; efficient or instantaneous win conditions; generally at least four turns. Fast mana, efficient tutors, free interaction, and compact combos are expected. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**ML implication:** valuable after the agent has learned stable tactical and strategic fundamentals. It introduces harsher mulligan decisions, tutor inference, combo threat assessment, and much greater sensitivity to small timing errors.

## Bracket 5: cEDH

**Official intent:** decks meticulously constructed for the cEDH metagame, optimized for efficiency and consistency, with razor-thin margins for error. Games may end on any turn. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**ML implication:** a specialist advanced curriculum, not the natural first step toward broad Commander competence. It heavily weights tutor lines, compact combos, mulligan discipline, stack wars, known metagame priors, and tournament incentives while often reducing ordinary combat relevance.

## Relevant current restrictions and policies

### Game Changers

The live framework excludes Game Changers from Brackets 1 and 2, permits up to three in Bracket 3, and permits unlimited Game Changers in Brackets 4 and 5. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/formats/commander "https://magic.wizards.com/en/formats/commander"))

The October 2025 published list contains 51 cards. Farewell and Biorhythm were added in February 2026, while Lutri, the Spellchaser was explicitly not added. That yields **53 current Game Changers by arithmetic from the two official updates**. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

For this project, legal permission is not the same as curriculum value. The first Bracket 3 lists should start with **zero Game Changers**, adding them only as controlled experimental factors later.

### Tutors

The old “few tutors” bracket restriction is obsolete. Wizards removed hard tutor restrictions in October 2025 and instead relies on the Game Changers list to identify the most efficient tutors. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

For ML purposes, low tutor density remains desirable even though it is no longer a formal bracket requirement. Repeatedly finding the same card:

1. reduces state and trajectory diversity;
2. can collapse games onto a small set of deterministic lines;
3. makes a fixed-deck policy overfit particular card identities;
4. reduces the value of singleton variance;
5. can inflate apparent policy quality through memorized tutor targets.

### Combos, extra turns, and land denial

Wizards’ detailed bracket guidance excludes mass land denial from Brackets 1–3. Bracket 3 is not intended to contain cheap two-card infinite combos that commonly occur during roughly the first six turns, and extra-turn effects should be uncommon and not deliberately chained or looped. The later October update emphasizes that a combo which frequently appears is not a good fit for the bracket even when technically delayed. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/introducing-commander-brackets-beta "https://magic.wizards.com/en/news/announcements/introducing-commander-brackets-beta"))

The first training lists should apply a stricter contract:

- no deterministic or infinite combo;
- no extra-turn cards;
- no mass land denial;
- no hard prison package;
- no alternate-win package such as poison;
- no tutor package intended to assemble a fixed line.

### Current banned list

The live Commander banned list includes, among other cards, Dockside Extortionist, Jeweled Lotus, Mana Crypt, Nadu, Winged Wisdom, Paradox Engine, and several historically banned cards. Lutri is currently banned only as a companion. Every candidate list must be revalidated against the live list when the exact 100 cards are locked. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/banned-restricted-list "https://magic.wizards.com/en/banned-restricted-list"))

---

# ML deck-selection criteria

## Candidate score

The **ML Curriculum Score /100** uses the following weighting.

| CategoryWeightWhat receives a high score |    |                                                               |
| ---------------------------------------- | -- | ------------------------------------------------------------- |
| Decision quality                         | 10 | Frequent nontrivial choices with observable consequences      |
| Planning horizon                         | 7  | Multi-turn resource and threat planning                       |
| Interaction                              | 9  | Meaningful opponent-responsive decisions                      |
| Combat                                   | 8  | Attacks, blocks, races, combat positioning                    |
| Hidden information                       | 6  | Reading removal, counters, protection and unknown cards       |
| Rules-complexity fit                     | 7  | Moderate reusable rules, not minimal or pathological          |
| Branching-factor fit                     | 6  | Enough alternatives to learn, without combinatorial explosion |
| Mana-complexity fit                      | 4  | Real sequencing choices without five-color overload           |
| Episode-length fit                       | 4  | Enough depth without excessive rollout cost                   |
| Engine burden                            | 4  | Reusable mechanics and tractable card implementation          |
| Mechanical coherence                     | 10 | Many cards reuse a compact set of concepts                    |
| Commander learning                       | 7  | Commander timing, tax, risk, zone choice and damage matter    |
| Commander independence                   | 3  | Commander matters but deck remains functional without it      |
| Tutor control                            | 4  | Low repeated-line compression                                 |
| Combo control                            | 7  | No required deterministic execution                           |
| Stax control                             | 4  | No lock-dominated play                                        |

The score intentionally treats complexity as **nonmonotonic**. A rules-complexity score of 1 is not automatically ideal: a deck with almost no interaction or decision depth may be easy to implement but poor for learning. Conversely, a score of 10 may be rich but impractical as the first curriculum.

## Matchup score

Matchups use a separate weighting:

| Matchup factorWeight           |    |
| ------------------------------ | -- |
| Expected power balance         | 18 |
| Strategic and decision depth   | 14 |
| Interaction density            | 12 |
| Combat opportunities           | 10 |
| Useful game-plan contrast      | 10 |
| Low deterministic matchup bias | 10 |
| Engine feasibility             | 9  |
| Commander-learning value       | 7  |
| Episode-length suitability     | 5  |
| Bracket parity                 | 5  |

The uncertainty on these scores is approximately **±3–5 points** until exact lists are implemented and simulated.

---

# Candidate pool

## Broad screen

The initial screen considered 24 commanders:

**Serious pool:** Akiri, Chevill, Neyith, Raff, Rishkar, Balmor, Florian, Adeline, Gisa and Geralf, and Rashmi.

**Deferred after screening:** Torens, Fist of the Angels; Jori En, Ruin Diver; Halana and Alena, Partners; Chishiro, the Shattered Blade; Emmara, Soul of the Accord; Kotori, Pilot Prodigy; Braids, Arisen Nightmare; Tovolar, Dire Overlord; Meren of Clan Nel Toth; Prosper, Tome-Bound; Wilhelt, the Rotcleaver; Sythis, Harvest’s Hand; Krenko, Mob Boss; and Aesi, Tyrant of Gyre Strait.

The principal rejection reasons were token-state explosion, commander-on-board-or-nothing behavior, multiplayer-specific politics, day/night or double-faced state, sacrifice-loop density, deterministic combo incentives, low combat relevance, hard value snowballing, or excessive trigger ordering.

## Serious-candidate table

“Bracket” means the **intended curated target list**, not every deck led by that commander.

| CommanderColorsArchetypeBracketRules ComplexityDecision ComplexityBranchingInteractionCombatTutor DensityCombo DependencyCommander DependencyMechanical CoherenceEngine BurdenML Score  |    |                                    |   |   |   |   |   |    |            |                            |             |    |              |        |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -- | ---------------------------------- | - | - | - | - | - | -- | ---------- | -------------------------- | ----------- | -- | ------------ | ------ |
| **Akiri, Fearless Voyager** ([EDHREC](https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded "https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded"))             | RW | Equipment midrange                 | 3 | 4 | 7 | 6 | 6 | 9  | Target 2–3 | LOW                        | MEDIUM      | 9  | Medium       | **88** |
| **Chevill, Bane of Monsters** ([EDHREC](https://edhrec.com/average-decks/chevill-bane-of-monsters/upgraded "https://edhrec.com/average-decks/chevill-bane-of-monsters/upgraded"))       | BG | Bounty/deathtouch control-midrange | 3 | 5 | 7 | 5 | 9 | 7  | Target 1–2 | LOW                        | MEDIUM      | 8  | Medium       | **87** |
| **Neyith of the Dire Hunt** ([EDHREC](https://edhrec.com/average-decks/neyith-of-the-dire-hunt/upgraded "https://edhrec.com/average-decks/neyith-of-the-dire-hunt/upgraded"))           | RG | Fight/combat midrange              | 3 | 5 | 8 | 7 | 8 | 10 | Target 0–1 | LOW                        | HIGH        | 9  | Small–medium | **86** |
| **Balmor, Battlemage Captain** ([EDHREC](https://edhrec.com/average-decks/balmor-battlemage-captain/upgraded "https://edhrec.com/average-decks/balmor-battlemage-captain/upgraded"))    | UR | Creature-spellslinger tempo        | 3 | 4 | 8 | 8 | 8 | 8  | Target 0–1 | LOW after tuning           | HIGH        | 9  | Small–medium | **84** |
| **Raff, Weatherlight Stalwart** ([EDHREC](https://edhrec.com/average-decks/raff-weatherlight-stalwart/upgraded "https://edhrec.com/average-decks/raff-weatherlight-stalwart/upgraded")) | WU | Token-spellslinger value           | 3 | 5 | 8 | 8 | 7 | 7  | Target 0–1 | LOW after tuning           | HIGH        | 8  | Small–medium | **83** |
| **Rishkar, Peema Renegade** ([EDHREC](https://edhrec.com/average-decks/rishkar-peema-renegade/upgraded "https://edhrec.com/average-decks/rishkar-peema-renegade/upgraded"))             | G  | Counters/ramp midrange             | 3 | 5 | 7 | 6 | 5 | 8  | Target 0–1 | LOW after mandatory tuning | MEDIUM      | 10 | Small–medium | **83** |
| **Florian, Voldaren Scion** ([EDHREC](https://edhrec.com/average-decks/florian-voldaren-scion/upgraded "https://edhrec.com/average-decks/florian-voldaren-scion/upgraded"))             | BR | Damage/value midrange              | 3 | 5 | 8 | 7 | 7 | 8  | Target 0–1 | LOW                        | MEDIUM–HIGH | 8  | Medium       | **82** |
| **Adeline, Resplendent Cathar** ([EDHREC](https://edhrec.com/average-decks/adeline-resplendent-cathar/upgraded "https://edhrec.com/average-decks/adeline-resplendent-cathar/upgraded")) | W  | Go-wide aggro                      | 3 | 5 | 7 | 8 | 5 | 10 | Target 1–2 | NONE/LOW                   | HIGH        | 9  | Medium       | **80** |
| **Gisa and Geralf** ([EDHREC](https://edhrec.com/average-decks/gisa-and-geralf/upgraded "https://edhrec.com/average-decks/gisa-and-geralf/upgraded"))                                   | UB | Zombie graveyard value             | 3 | 6 | 8 | 7 | 6 | 7  | Target 1–2 | LOW after mandatory tuning | MEDIUM      | 9  | Medium       | **79** |
| **Rashmi, Eternities Crafter** ([EDHREC](https://edhrec.com/average-decks/rashmi-eternities-crafter/upgraded "https://edhrec.com/average-decks/rashmi-eternities-crafter/upgraded"))    | UG | Flash/control value                | 3 | 6 | 9 | 8 | 9 | 4  | Target 1–2 | LOW/MEDIUM                 | HIGH        | 8  | Medium–large | **78** |

---

# Candidate analysis

## 1. Akiri, Fearless Voyager

**Color identity:** Boros
**Archetype:** Equipment midrange
**Target power:** middle Bracket 3
**Primary win condition:** repeated equipped-creature combat
**Secondary win condition:** commander damage or protected attrition
**Episode length:** MEDIUM
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Akiri rewards attacking with equipped creatures and can convert Equipment into temporary protection. This creates repeated choices over whether to deploy another creature, equip before combat, leave mana available, hold Akiri until protection is available, or detach an Equipment to preserve a high-value threat.

**ML profile:** Rules 4, Decisions 7, Branching 6, Planning 7, Interaction 6, Combat 9, Hidden information 6, Mana 5, Commander-learning value 9.

The current EDHREC user-bracketed Bracket 3 aggregate contained 161 decks and was strongly concentrated around Equipment, aggro, artifacts, and Voltron. Its average list included nine instants, 25 artifacts, several Equipment tutors, Teferi’s Protection, Sunforger, multiple Swords, Stoneforge Mystic, Stonehewer Giant, Open the Armory, and Steelshaper’s Gift. ([EDHREC](https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded "https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded"))

**Tutor density:** the public aggregate contains approximately six to eight broad Equipment-search or toolbox effects depending on definition. The first training list should use two or three, preferably slower or narrower search effects rather than repeatedly finding the single best Equipment.

**Game Changers:** the public aggregate includes Teferi’s Protection. The training target should use zero.

**Combo dependency:** LOW, but packages involving Sunforger, instant-speed re-equipping, Equipment copying, and recursive sacrifice can produce disproportionately large decision trees. None is required for the archetype.

**Stax:** NONE in the target.

**Commander dependency:** MEDIUM. The deck can still cast creatures, Equipment, removal, and protection without Akiri, but loses card flow and its most distinctive protection decision.

**Likely tuning:**

- remove Teferi’s Protection;
- remove or sharply reduce the Sunforger toolbox;
- avoid Equipment-copy subgames;
- avoid excessive “attach without paying equip cost” chains;
- keep approximately 12–16 Equipment, ordinary equip costs, several creatures that remain useful unequipped, and bounded protection.

**Engine burden:** Equipment attachment and unattachment are represented in Argentum’s general engine primitives, but an exact Akiri implementation was not located in the repository search. This looks more like a missing card definition plus deck-level coverage work than a fundamental Commander gap.

---

## 2. Chevill, Bane of Monsters

**Color identity:** Golgari
**Archetype:** bounty/deathtouch interactive midrange
**Target power:** middle Bracket 3
**Primary win condition:** removal-fueled card advantage followed by creature combat
**Secondary win condition:** deathtouch attrition and incremental life/card advantage
**Episode length:** LONG
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Chevill provides an unusually useful learning loop:

1. identify the opponent’s strategically relevant permanent;
2. place the bounty counter;
3. decide whether removing it immediately is worthwhile;
4. account for protection, exile, bounce, sacrifice, or replacement effects;
5. convert successful removal into resources.

**ML profile:** Rules 5, Decisions 7, Branching 5, Planning 8, Interaction 9, Combat 7, Hidden information 7, Mana 4, Commander-learning value 8.

The current EDHREC Bracket 3 aggregate represented 97 user-bracketed decks and was concentrated around control, deathtouch, midrange, and lifegain. Its average list included Abrupt Decay, Assassin’s Trophy, Beast Within, Bite Down, Bounty Board, Bounty Hunter, Deathreap Ritual, Demonic Tutor, Fynn, the Fangbearer, Varragoth, and numerous deathtouch creatures. ([EDHREC](https://edhrec.com/average-decks/chevill-bane-of-monsters/upgraded "https://edhrec.com/average-decks/chevill-bane-of-monsters/upgraded"))

**Tutor density:** approximately two or three meaningful effects in the aggregate. Target one or two.

**Game Changers:** Demonic Tutor is a Game Changer and should be removed. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**Combo dependency:** LOW. Thornbite Staff, repeatable pingers, deathtouch, sacrifice effects, or Revel in Riches can nevertheless create hidden combo-like or alternate-win incentives. These are unnecessary.

**Stax:** NONE in the target.

**Commander dependency:** MEDIUM. Chevill accelerates the removal plan, but a properly built deck remains a functional Golgari creature/removal deck without him.

**Likely tuning:**

- remove Demonic Tutor;
- remove Fynn and poison as an alternate victory axis;
- remove Revel in Riches as an alternate win;
- avoid politics-oriented “opponent’s creature” cards that lose meaning in 1v1;
- avoid repeatable deathtouch-pinger engines;
- retain targeted removal, fight/bite effects, deathtouch blockers, modest recursion, and normal combat finishers.

**Engine burden:** the likely difficult element is not deathtouch or removal but tracking a bounty-marked opposing permanent through destruction and last-known information. This is probably a reusable medium-sized mechanic if Argentum lacks the exact conditional death trigger. An exact Chevill definition was not located.

---

## 3. Neyith of the Dire Hunt

**Color identity:** Gruul
**Archetype:** fight and forced-block combat midrange
**Target power:** middle Bracket 3
**Primary win condition:** large-creature combat
**Secondary win condition:** commander damage and fight-based board control
**Episode length:** MEDIUM
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Neyith is almost a purpose-built combat curriculum. The policy must decide which creature to enlarge, when to force a block, which fights are favorable, whether card draw justifies exposing a creature, and whether to spend mana before combat or preserve interaction.

**ML profile:** Rules 5, Decisions 8, Branching 7, Planning 7, Interaction 8, Combat 10, Hidden information 5, Mana 5, Commander-learning value 8.

The current EDHREC user-bracketed aggregate covers 144 decks and includes Fight, Aggro, Midrange, and Ramp shells. Representative cards include Anzrag, the Quake-Mole; Apex Altisaur; Brash Taunter; Boxing Ring; several fight spells; Unnatural Growth; Vigor; and large trampling creatures. ([EDHREC](https://edhrec.com/average-decks/neyith-of-the-dire-hunt/upgraded "https://edhrec.com/average-decks/neyith-of-the-dire-hunt/upgraded"))

A recently crawled Archidekt list presents a 100-card, estimated Bracket 3 Neyith shell, confirming that the archetype can be expressed at the intended bracket, although platform bracket estimation is not official certification. ([Archidekt](https://archidekt.com/decks/23630500/dire_hunt "https://archidekt.com/decks/23630500/dire_hunt"))

**Tutor density:** low; zero or one meaningful nonland tutor is sufficient.

**Game Changers:** none is required. Target zero.

**Combo dependency:** LOW.

**Stax:** NONE.

**Commander dependency:** HIGH. The deck still fields creatures and fight spells, but Neyith provides both the card engine and forced-combat identity.

**Likely tuning:**

- remove Apex Altisaur or carefully cap its iterative fight resolution;
- remove Brash Taunter if damage redirection creates loopish states;
- remove Anzrag if repeated combat phases are not yet supported;
- avoid global power-doubling stacks;
- retain ordinary fight spells, must-block effects, trample, protection and medium-sized creatures.

**Engine burden:** Argentum already contains general fight and must-block executors. The commander may still require a specific combined trigger and beginning-of-combat payment implementation, but the core mechanics appear reusable rather than novel.

---

## 4. Balmor, Battlemage Captain

**Color identity:** Izzet
**Archetype:** creature-spellslinger tempo
**Target power:** middle Bracket 3
**Primary win condition:** spell-amplified creature combat
**Secondary win condition:** bounded burn and evasive pressure
**Episode length:** SHORT
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Balmor teaches the agent to sequence creature deployment, cantrips, removal, protection, and combat. Casting the same spell before combat, during combat, or after combat can produce materially different outcomes.

**ML profile:** Rules 4, Decisions 8, Branching 8, Planning 6, Interaction 8, Combat 8, Hidden information 8, Mana 6, Commander-learning value 7.

The current Bracket 3 EDHREC aggregate represented 153 user-bracketed decks, mainly tagged Spellslinger, Aggro, Tokens, and Prowess. Its average list includes numerous cheap instants and sorceries, token producers, Veyran, Harmonic Prodigy, and Storm-Kiln Artist. ([EDHREC](https://edhrec.com/average-decks/balmor-battlemage-captain/upgraded "https://edhrec.com/average-decks/balmor-battlemage-captain/upgraded"))

**Tutor density:** zero or one.

**Game Changers:** none is required. Target zero.

**Combo dependency:** public Balmor lists frequently contain cards associated with storm, ritual, recursion, cost-reduction, or duplicated cast triggers. The target classification is LOW only after those lines are removed.

**Stax:** NONE.

**Commander dependency:** HIGH. Without Balmor, many cheap spells no longer translate efficiently into combat damage.

**Likely tuning:**

- remove Storm-Kiln Artist and ritual-like mana explosions;
- remove Veyran and Harmonic Prodigy;
- remove buyback, spell-copy, or recursive storm loops;
- avoid Impact Tremors-style noncombat shortcuts;
- retain cheap interaction, cantrips, modest token generation, prowess creatures and combat-oriented spell sequencing.

**Engine burden:** an Argentum Balmor definition exists and uses generic “cast instant or sorcery,” group stat modification, and temporary trample primitives. However, its file is generated and explicitly states that rules review and a passing scenario test are still required. It is therefore **definition present, conformance unproven**.

---

## 5. Raff, Weatherlight Stalwart

**Color identity:** Azorius
**Archetype:** token-spellslinger interactive value
**Target power:** middle Bracket 3
**Primary win condition:** token and small-creature combat
**Secondary win condition:** activated team pump and flying pressure
**Episode length:** MEDIUM
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Raff creates a valuable tension between tapping creatures to draw and retaining them as attackers or blockers. The deck must also decide when to spend mana on interaction versus building a battlefield.

**ML profile:** Rules 5, Decisions 8, Branching 8, Planning 7, Interaction 7, Combat 7, Hidden information 8, Mana 6, Commander-learning value 7.

The current Bracket 3 aggregate represents 55 user-bracketed lists and is primarily tagged Tokens, Spellslinger, Control, and Midrange. It includes cantrips, counterspells, token makers, Archmage Emeritus, and Akroma’s Will. ([EDHREC](https://edhrec.com/average-decks/raff-weatherlight-stalwart/upgraded "https://edhrec.com/average-decks/raff-weatherlight-stalwart/upgraded"))

A current public Archidekt list is 100 cards and estimated Bracket 3. Its categories show substantial card selection, card advantage, cost reduction and interaction, but it also includes Grand Arbiter Augustin IV, which is currently a Game Changer. ([Archidekt](https://archidekt.com/decks/3528069/raff_weatherlight_stalwart "https://archidekt.com/decks/3528069/raff_weatherlight_stalwart"))

**Tutor density:** zero or one in the target.

**Combo dependency:** LOW after tuning.

**Stax:** current shells can drift toward Azorius taxes; target NONE.

**Commander dependency:** HIGH. Raff is the bridge between spells, creature tapping and card flow.

**Likely tuning:**

- remove Grand Arbiter Augustin IV;
- remove broad tax effects;
- avoid repeated spell-copy or cost-reduction stacks;
- cap token production;
- avoid large X-draw and top-deck manipulation packages;
- retain ordinary cantrips, token makers, bounded counterspells, removal and the tap-two-creatures draw decision.

**Engine burden:** Argentum contains support for tapping multiple creatures as a cost. Raff’s exact combined trigger and activated team pump still require direct coverage verification.

---

## 6. Rishkar, Peema Renegade

**Color identity:** mono-green
**Archetype:** +1/+1 counters and creature-based ramp
**Target power:** lower-to-middle Bracket 3
**Primary win condition:** counter-enhanced creature combat
**Secondary win condition:** convert creatures into mana and cast larger threats
**Episode length:** MEDIUM
**Verdict:** **GOOD WITH CURRICULUM TUNING, BUT DE-COMBO IS MANDATORY**

Rishkar is mechanically coherent: counters represent both combat development and mana infrastructure. The commander’s enter-the-battlefield targets create meaningful choices, while removal can invalidate both board presence and future mana.

**ML profile:** Rules 5, Decisions 7, Branching 6, Planning 8, Interaction 5, Combat 8, Hidden information 4, Mana 7, Commander-learning value 8.

The current user-bracketed Bracket 3 aggregate includes Herd Baloth and Ivy Lane Denizen in the same average shell, as well as Seedborn Muse. ([EDHREC](https://edhrec.com/average-decks/rishkar-peema-renegade/upgraded "https://edhrec.com/average-decks/rishkar-peema-renegade/upgraded"))

Herd Baloth plus Ivy Lane Denizen forms a deterministic infinite loop producing arbitrarily many counters, creature tokens, and creature-entering events. Commander Spellbook classifies the line as a two-card combo and documents the repeating trigger sequence. ([Commander Spellbook](https://commanderspellbook.com/combo/2850-3197/ "https://commanderspellbook.com/combo/2850-3197/"))

**Tutor density:** target zero or one.

**Game Changers:** Seedborn Muse is currently a Game Changer and should be removed. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

**Combo dependency:** MEDIUM in the stock aggregate; LOW only after mandatory removal of the Ivy Lane Denizen–Herd Baloth pair.

**Stax:** NONE.

**Commander dependency:** MEDIUM. The deck loses efficiency without Rishkar but remains a normal counter-based creature deck.

**Likely tuning:**

- remove either Ivy Lane Denizen or Herd Baloth—preferably both;
- remove Seedborn Muse;
- avoid The Ozolith or replacement-heavy counter packages initially;
- avoid untap engines and mana-doubling loops;
- retain straightforward counter placement, counter payoffs, fight/removal, trample and protection.

**Engine burden:** Argentum contains generic support for granting abilities to creatures selected by counter-based filters, suggesting that Rishkar may be a relatively reusable card definition rather than a large engine addition. Exact definition and scenario coverage were not located.

---

## 7. Florian, Voldaren Scion

**Color identity:** Rakdos
**Archetype:** damage-enabled card-selection midrange
**Target power:** middle Bracket 3
**Primary win condition:** creature combat plus bounded direct damage
**Secondary win condition:** postcombat card-selection advantage
**Episode length:** MEDIUM
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Florian links combat and burn to postcombat planning. The policy must decide how much damage to pursue, whether to expose attackers, which spell to use before combat, and whether it can actually cast the selected card before the permission expires.

**ML profile:** Rules 5, Decisions 8, Branching 7, Planning 8, Interaction 7, Combat 8, Hidden information 7, Mana 5, Commander-learning value 8.

The current Bracket 3 EDHREC aggregate includes burn, spellslinger, aggro and group-slug shells. Notable cards include Heartless Hidetsugu, Jeska’s Will, Neheb, Prosper, Storm-Kiln Artist and large life-loss effects. ([EDHREC](https://edhrec.com/average-decks/florian-voldaren-scion/upgraded "https://edhrec.com/average-decks/florian-voldaren-scion/upgraded"))

A recently crawled 100-card Archidekt list is estimated Bracket 3 and provides a concrete public reference for the archetype. ([Archidekt](https://archidekt.com/decks/23279267/florian_voldaren_scion "https://archidekt.com/decks/23279267/florian_voldaren_scion"))

**Tutor density:** zero or one.

**Game Changers:** Jeska’s Will is a Game Changer and should be removed.

**Combo dependency:** LOW.

**Stax:** NONE.

**Commander dependency:** MEDIUM–HIGH. The deck can still attack and burn, but loses its primary card-selection engine.

**Likely tuning:**

- remove Jeska’s Will;
- remove ritual/storm mana engines;
- remove Dragon’s Approach-style repeated-card plans and Thrumming Stone;
- remove multiplayer group-slug cards whose 1v1 behavior is disproportionately strong or trivial;
- retain normal creatures, removal, small burn spells and bounded impulse-draw effects.

**Engine burden:** Florian requires tracking total opponent life lost during the turn, selecting from the top X cards, placing the remainder on the bottom in random order, and enforcing temporary play permission. This looks like a likely medium reusable-mechanic addition if any part is missing.

---

## 8. Adeline, Resplendent Cathar

**Color identity:** mono-white
**Archetype:** go-wide creature aggro
**Target power:** middle Bracket 3
**Primary win condition:** wide combat
**Secondary win condition:** commander damage or anthem-enhanced attacks
**Episode length:** SHORT
**Verdict:** **GOOD WITH CURRICULUM TUNING**

Adeline appears simple but is not trivial. Attacker combinations, token creation, protection timing, combat races, anthem math and board-wipe exposure can create substantial branching.

**ML profile:** Rules 5, Decisions 7, Branching 8, Planning 6, Interaction 5, Combat 10, Hidden information 5, Mana 3, Commander-learning value 7.

The current Bracket 3 aggregate represents 331 user-bracketed decks and is dominated by Tokens, Aggro, Humans and Voltron tags. It contains Farewell, Smothering Tithe, Teferi’s Protection, token doubling and Cathars’ Crusade. ([EDHREC](https://edhrec.com/average-decks/adeline-resplendent-cathar/upgraded "https://edhrec.com/average-decks/adeline-resplendent-cathar/upgraded"))

All three named cards are Game Changers under the current policy. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-february-9-2026 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-february-9-2026"))

A current public 100-card Archidekt Adeline list is estimated Bracket 3. ([Archidekt](https://archidekt.com/decks/7155520/adeline_resplendent_cathar "https://archidekt.com/decks/7155520/adeline_resplendent_cathar"))

**Tutor density:** target one or two.

**Combo dependency:** NONE/LOW.

**Stax:** LOW in ordinary lists; target NONE.

**Commander dependency:** HIGH. Adeline supplies both threat size and continuous token production.

**Likely tuning:**

- remove Farewell, Smothering Tithe and Teferi’s Protection;
- remove Cathars’ Crusade;
- remove token doublers;
- remove tax/stax creatures;
- limit repeatable mass protection;
- retain normal Humans, removal, one or two conventional sweepers, bounded anthem effects and ordinary combat tricks.

**Engine burden:** in 1v1, Adeline creates one attacking token per attack rather than a token for several opponents. Nevertheless, “tapped and attacking” creation, attack-target assignment and variable power require exact conformance. This is likely a medium card/mechanic burden.

---

## 9. Gisa and Geralf

**Color identity:** Dimir
**Archetype:** Zombie graveyard value
**Target power:** middle Bracket 3 after de-combo
**Primary win condition:** recursive Zombie combat and attrition
**Secondary win condition:** bounded drain and graveyard card advantage
**Episode length:** LONG
**Verdict:** **GOOD WITH CURRICULUM TUNING, NOT A FIRST-PAIR FAVORITE**

This is the strongest graveyard candidate. It teaches self-mill, graveyard-as-resource reasoning, cast timing, interaction with graveyard hate, and once-per-turn permissions.

**ML profile:** Rules 6, Decisions 8, Branching 7, Planning 8, Interaction 6, Combat 7, Hidden information 6, Mana 5, Commander-learning value 8.

The current Bracket 3 aggregate represents 493 user-bracketed decks and is concentrated around reanimation, Zombies, mill and tokens. Its average list includes Gravecrawler, Carrion Feeder, Ashnod’s Altar, Phyrexian Altar, Rooftop Storm and Buried Alive. ([EDHREC](https://edhrec.com/average-decks/gisa-and-geralf/upgraded "https://edhrec.com/average-decks/gisa-and-geralf/upgraded"))

Those cards create several deterministic loops:

- Gravecrawler + Rooftop Storm + Carrion Feeder: infinite counters and repeated death/enter/leave events;
- Gravecrawler + Rooftop Storm + Ashnod’s Altar: infinite colorless mana and repeated death/enter/leave events;
- Gravecrawler + Phyrexian Altar + another Zombie: repeatable infinite death and enter/leave events. ([Commander Spellbook](https://commanderspellbook.com/combo/2034-2452-2577/ "https://commanderspellbook.com/combo/2034-2452-2577/"))

**Tutor density:** target one or two; Buried Alive and Entomb-like effects are functionally powerful setup tutors even when they do not put cards into hand.

**Game Changers:** none is required; remove any efficient Game Changer tutor appearing in the selected list.

**Combo dependency:** MEDIUM in common shells; LOW after mandatory curation.

**Stax:** LOW; target NONE.

**Commander dependency:** MEDIUM. Zombies and graveyard effects still operate without the commander.

**Likely tuning:**

- remove Gravecrawler;
- remove Rooftop Storm;
- remove Ashnod’s Altar and Phyrexian Altar;
- remove free-sacrifice loops;
- reduce Buried Alive/Entomb-style setup;
- retain self-mill, ordinary Zombie lords, one-Zombie-per-turn graveyard casting, normal removal and graveyard interaction.

**Engine burden:** Gisa and Geralf already has a hand-authored-looking Argentum definition and scenario tests covering ETB mill, Zombie filtering, once-per-turn spending, and refresh on the next turn. That is meaningful evidence of working reusable graveyard-cast support, although it does not establish coverage for the remaining 99 cards.

---

## 10. Rashmi, Eternities Crafter

**Color identity:** Simic
**Archetype:** flash/control value
**Target power:** middle-to-upper Bracket 3
**Primary win condition:** accumulate resource superiority, then win through large creatures
**Secondary win condition:** tempo and repeated instant-speed value
**Episode length:** VERY LONG
**Verdict:** **POOR FIRST CURRICULUM; STRONG LATER-STAGE CANDIDATE**

Rashmi has excellent strategic depth but poor first-environment characteristics. A large percentage of meaningful decisions occur on the opponent’s turn, many involve pass/hold/counter timing, and combat can become secondary.

**ML profile:** Rules 6, Decisions 9, Branching 8, Planning 9, Interaction 9, Combat 4, Hidden information 9, Mana 6, Commander-learning value 7.

The current Bracket 3 aggregate represents 193 user-bracketed decks and is concentrated around Control, Spellslinger, Flash and Counterspells. It includes Craterhoof Behemoth and many instant-speed effects; commonly associated lists also contain Cyclonic Rift, Seedborn Muse, top-deck control and Hullbreaker Horror-style soft-lock potential. ([EDHREC](https://edhrec.com/average-decks/rashmi-eternities-crafter/upgraded "https://edhrec.com/average-decks/rashmi-eternities-crafter/upgraded"))

Current public Archidekt references show 100-card Rashmi decks estimated as Bracket 3. ([Archidekt](https://archidekt.com/decks/23846957/rashmi_eternities_crafter "https://archidekt.com/decks/23846957/rashmi_eternities_crafter"))

**Tutor density:** target one or two.

**Game Changers:** remove Cyclonic Rift and Seedborn Muse if present.

**Combo dependency:** LOW/MEDIUM depending on the selected control finishers.

**Stax:** LOW to MEDIUM in typical shells; target LOW.

**Commander dependency:** HIGH. Rashmi is the central value engine.

**Likely tuning:**

- remove Cyclonic Rift;
- remove Seedborn Muse;
- remove top-deck locks;
- remove Hullbreaker Horror soft-lock packages;
- reduce counterspell density;
- remove free-casting chains;
- increase ordinary creatures and combat finishers.

Even after tuning, Rashmi remains better suited to a later hidden-information and stack-timing curriculum.

---

# Card-diversity and mechanical-coherence estimates

These ranges refer to future curated 100-card lists with approximately 34–38 lands. “Mechanically distinct” means cards that introduce a materially new rule or decision pattern, not merely different names or power/toughness values.

| CommanderUnique nonlandsMechanically distinct cardsKeyword/mechanic familiesDecision-pattern familiesCoherence |       |       |       |       |    |
| -------------------------------------------------------------------------------------------------------------- | ----- | ----- | ----- | ----- | -- |
| Akiri                                                                                                          | 63–67 | 22–28 | 10–14 | 12–16 | 9  |
| Chevill                                                                                                        | 62–66 | 24–30 | 9–13  | 13–17 | 8  |
| Neyith                                                                                                         | 62–66 | 20–26 | 10–14 | 12–15 | 9  |
| Balmor                                                                                                         | 64–67 | 20–27 | 7–11  | 14–18 | 9  |
| Raff                                                                                                           | 63–67 | 25–32 | 9–13  | 15–19 | 8  |
| Rishkar                                                                                                        | 62–66 | 19–25 | 8–12  | 11–14 | 10 |
| Florian                                                                                                        | 63–66 | 25–32 | 8–12  | 14–18 | 8  |
| Adeline                                                                                                        | 63–67 | 22–30 | 10–15 | 13–17 | 9  |
| Gisa and Geralf                                                                                                | 62–66 | 25–33 | 10–15 | 15–20 | 9  |
| Rashmi                                                                                                         | 62–66 | 30–38 | 12–18 | 18–24 | 8  |

Rishkar has the highest mechanical coherence but lower interaction. Rashmi has many strategically related cards, yet each may create a distinct timing or information pattern. Akiri provides the strongest compromise between singleton diversity and repeated decision structure.

---

# Matchup candidates

## Balance-evidence limitation

EDHREC and public deckbuilders provide card-usage and deck-construction evidence, not a controlled 1v1 head-to-head matrix. Commander tournament databases largely concern cEDH rather than curated Bracket 3 midrange pairings. No defensible numerical win rates were found for these exact pairs.

All balance judgments below are therefore structural inference. They must be replaced by measured results after exact-list implementation.

---

## 1. Akiri, Fearless Voyager vs Chevill, Bane of Monsters

**Target:** curated Bracket 3 vs curated Bracket 3
**Power balance:** plausible, unmeasured
**Game-plan contrast:** Equipment combat versus removal/deathtouch attrition
**Episode length:** MEDIUM–LONG
**Deterministic-bias risk:** MEDIUM–LOW
**Engine burden:** MEDIUM
**ML Curriculum Score:** **91/100**

This pairing has the best combined distribution of combat, interaction, target selection, protection, commander timing, hidden information and long-horizon resource management.

Chevill must decide which permanent deserves a bounty counter and whether removing it is worth consuming interaction. Akiri must decide how much material to place onto one creature, whether to attack into deathtouch, and whether to leave mana or Equipment available for protection.

The primary risk is that Chevill may have too much natural removal, while Akiri’s indestructibility can make destroy-only interaction inefficient. The correct solution is list calibration, not abandonment:

- Chevill should use a mixture of destroy, exile, -X/-X, edict and artifact interaction;
- Akiri should not have excessive protection or recursion;
- equipment and creature counts must prevent both “all equipment, no bearer” and unstoppable Voltron draws;
- Chevill’s deathtouch count should support combat without turning every attack into a trivial refusal.

This pairing has particularly strong long-term transfer to Aura, Equipment, modified-creature, bounty, midrange and protection-based decks.

---

## 2. Neyith of the Dire Hunt vs Raff, Weatherlight Stalwart

**Target:** Bracket 3 vs Bracket 3
**Power balance:** plausible but sensitive
**Game-plan contrast:** forced combat versus interactive token-spellslinger
**Episode length:** MEDIUM
**Deterministic-bias risk:** MEDIUM
**Engine burden:** MEDIUM
**ML Curriculum Score:** **87/100**

This pairing produces both stack and combat decisions. Neyith can pressure Raff’s token battlefield through fights and forced blocks; Raff can protect creatures, counter major threats, tap creatures for cards, and use tokens to manipulate combat.

The potential matchup bias is significant:

- cheap Raff tokens may trivialize must-block requirements;
- repeated fights may make Raff unable to retain a battlefield;
- counterspells may make Neyith excessively commander-dependent;
- first-player advantage may be pronounced.

It is an excellent second matchup or alternative first matchup after a small amount of balance testing.

---

## 3. Rishkar, Peema Renegade vs Balmor, Battlemage Captain

**Target:** Bracket 3 vs Bracket 3
**Power balance:** plausible
**Game-plan contrast:** permanent-based growth versus spell-driven tempo
**Episode length:** MEDIUM
**Deterministic-bias risk:** MEDIUM
**Engine burden:** SMALL–MEDIUM
**ML Curriculum Score:** **84/100**

Rishkar builds a persistent board and converts it into mana. Balmor deploys smaller threats, interacts at instant speed, and times spells around combat.

This is attractive for implementation because many core primitives already appear represented in Argentum, including Balmor itself. It also creates a strong training signal: Balmor must identify which Rishkar creature is simultaneously a combat threat and mana source, while Rishkar must decide whether to expand into possible removal or preserve protection.

The drawbacks are lower green interaction density and potential tempo polarization. The Ivy Lane Denizen–Herd Baloth combo and Balmor storm engines must be removed before any experiment.

---

## 4. Adeline, Resplendent Cathar vs Florian, Voldaren Scion

**Target:** Bracket 3 vs Bracket 3
**Power balance:** plausible but volatile
**Game-plan contrast:** go-wide aggro versus damage-enabled Rakdos value
**Episode length:** SHORT–MEDIUM
**Deterministic-bias risk:** MEDIUM–HIGH
**Engine burden:** MEDIUM
**ML Curriculum Score:** **82/100**

This creates fast episodes and a very clear penalty for poor blocking, removal timing or overextension. Florian benefits from dealing damage but may be forced to spend removal simply to survive. Adeline must decide how quickly to commit into Rakdos sweepers and removal.

The matchup may be too draw-dependent:

- unanswered Adeline can snowball rapidly;
- a timely sweeper can collapse the white deck;
- Florian can turn damage into further card access;
- mono-white recovery may be substantially worse.

This is valuable as a later aggression-and-recovery slice rather than the safest first environment.

---

## 5. Akiri, Fearless Voyager vs Gisa and Geralf

**Target:** Bracket 3 vs Bracket 3
**Power balance:** uncertain
**Game-plan contrast:** Equipment board development versus graveyard value
**Episode length:** LONG
**Deterministic-bias risk:** MEDIUM–HIGH
**Engine burden:** MEDIUM–HIGH
**ML Curriculum Score:** **80/100**

This pairing introduces graveyard reasoning while keeping substantial combat. Akiri can pressure before the Zombie deck accumulates value; Gisa and Geralf can win prolonged attrition.

Its weakness is side-mechanic polarization. Artifact removal can disproportionately affect Akiri, while graveyard hate can disproportionately affect Gisa and Geralf. A single hate card may decide games more than general policy quality. The graveyard deck also requires mandatory combo removal.

This is an excellent C2 matchup once the first policy has stable zone and combat representations.

---

## 6. Rashmi, Eternities Crafter vs Florian, Voldaren Scion

**Target:** Bracket 3 vs Bracket 3
**Power balance:** uncertain
**Game-plan contrast:** draw-go control versus proactive damage/value
**Episode length:** VERY LONG
**Deterministic-bias risk:** MEDIUM
**Engine burden:** MEDIUM–HIGH
**ML Curriculum Score:** **77/100**

This pairing offers excellent hidden-information and timing value, but combat is less central and many decisions become repeated pass/hold/counter choices. Rashmi may dominate long games, while Florian can be unable to convert postcombat access into cards through countermagic.

It is better used as an advanced hidden-information and stack-control environment.

---

# Matchup scorecard

| MatchupBalance /18Depth /14Interaction /12Combat /10Contrast /10Bias control /10Engine /9Commander learning /7Episode /5Bracket /5**Total** |    |    |    |    |   |   |   |   |   |   |        |
| ------------------------------------------------------------------------------------------------------------------------------------------- | -- | -- | -- | -- | - | - | - | - | - | - | ------ |
| **Akiri vs Chevill**                                                                                                                        | 16 | 13 | 11 | 9  | 9 | 8 | 8 | 7 | 5 | 5 | **91** |
| **Neyith vs Raff**                                                                                                                          | 15 | 13 | 11 | 10 | 9 | 7 | 7 | 6 | 4 | 5 | **87** |
| **Rishkar vs Balmor**                                                                                                                       | 14 | 12 | 10 | 9  | 9 | 7 | 8 | 6 | 4 | 5 | **84** |
| **Adeline vs Florian**                                                                                                                      | 14 | 12 | 9  | 10 | 9 | 6 | 7 | 6 | 4 | 5 | **82** |
| **Akiri vs Gisa/Geralf**                                                                                                                    | 13 | 12 | 10 | 8  | 9 | 6 | 6 | 6 | 5 | 5 | **80** |
| **Rashmi vs Florian**                                                                                                                       | 13 | 13 | 11 | 5  | 9 | 7 | 6 | 6 | 2 | 5 | **77** |

---

# Representative decklist analysis

## Methodological note

EDHREC “average deck” pages are **aggregates**, not existing 100-card lists. The pages state that their bracket-filtered samples include only decks with user-set brackets and exclude auto-detected bracket assignments. They are useful for identifying common cards and archetype drift but must not be presented as a real deck. ([EDHREC](https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded "https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded"))

Archidekt references are actual public 100-card lists. Their “estimated bracket” field is platform metadata, not official Wizards certification. Every exact list must still be checked for:

- current Commander legality;
- exact 100-card count;
- color identity;
- current bans;
- Game Changers;
- hidden combos;
- Argentum implementation;
- external decision control.

No cards from separate sources are being combined here and presented as an existing deck.

---

## Matchup reference 1: Akiri vs Chevill

### Akiri reference

The current EDHREC Bracket 3 aggregate has 161 user-bracketed lists and a strong Equipment core. A separate public Archidekt Akiri list is 100 cards and estimated Bracket 3. ([EDHREC](https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded "https://edhrec.com/average-decks/akiri-fearless-voyager/upgraded"))

**Observed composition risks:**

- Teferi’s Protection is a Game Changer;
- numerous Equipment tutors strongly compress game diversity;
- Sunforger creates a hidden instant toolbox;
- Leonin Shikari and free/instant-speed attachment effects enlarge the response tree;
- Kaldra Compleat and high-end Swords can create large matchup swings;
- Equipment copying introduces extra object-identity and attachment state.

**Curriculum transformation:** use the source only as an archetype reference. A training version should remove Teferi’s Protection, Sunforger, most tutors, copy effects and phasing/protection outliers while retaining normal creatures, Equipment, equip costs and interaction.

### Chevill reference

The current EDHREC Bracket 3 aggregate has 97 user-bracketed lists. Current public Archidekt references include 100-card decks estimated Bracket 3, including a recent deathtouch-oriented list. ([EDHREC](https://edhrec.com/average-decks/chevill-bane-of-monsters/upgraded "https://edhrec.com/average-decks/chevill-bane-of-monsters/upgraded"))

**Observed composition risks:**

- Demonic Tutor is both a Game Changer and a universal tutor;
- Fynn introduces poison as an alternate victory condition;
- Varragoth is a repeated tutor;
- Thornbite Staff and deathtouch pingers can produce highly asymmetric board control;
- Revel in Riches introduces a noncombat alternate win;
- multiplayer-politics and opponent-reanimation cards may become strange in 1v1.

**Curriculum transformation:** retain bounty counters, ordinary target removal, deathtouch blockers, limited fight/bite interaction and fair recursion. Remove poison, alternate wins, universal tutors and repeatable ping engines.

---

## Matchup reference 2: Neyith vs Raff

### Neyith reference

EDHREC currently aggregates 144 user-bracketed Bracket 3 decks. Public Archidekt references include current 100-card Neyith lists estimated Bracket 3. ([EDHREC](https://edhrec.com/average-decks/neyith-of-the-dire-hunt/upgraded "https://edhrec.com/average-decks/neyith-of-the-dire-hunt/upgraded"))

**Observed composition risks:**

- Apex Altisaur can generate long, repeated fight sequences;
- Anzrag introduces additional-combat recursion;
- Brash Taunter creates damage-reflection states;
- Unnatural Growth, Xenagos and Zopandrel can create extreme damage multiplication;
- Vigor introduces replacement-effect complexity.

**Curriculum transformation:** retain one-target fight spells, ordinary trample, must-block effects, protection and a moderate creature curve. Exclude repeated combat, iterative board-wide fight chains and stacked power doubling.

### Raff reference

The current EDHREC Bracket 3 aggregate contains 55 decks. A public Archidekt Raff list is 100 cards and estimated Bracket 3. ([EDHREC](https://edhrec.com/average-decks/raff-weatherlight-stalwart/upgraded "https://edhrec.com/average-decks/raff-weatherlight-stalwart/upgraded"))

**Observed composition risks:**

- Grand Arbiter Augustin IV is a Game Changer and introduces tax/stax behavior;
- high cantrip and cost-reducer density can create long spell chains;
- broad token production can explode combat branching;
- spell-copy effects multiply triggers and choices;
- large protection effects can create blowouts.

**Curriculum transformation:** retain Raff’s core tap-two-creatures choice, ordinary cantrips, modest token generation, limited counterspells and creature combat. Remove Grand Arbiter, broad taxes, trigger duplication and unconstrained token engines.

---

## Matchup reference 3: Rishkar vs Balmor

### Rishkar reference

The current Bracket 3 aggregate contains both Ivy Lane Denizen and Herd Baloth, plus Seedborn Muse. A separate public 100-card Rishkar list is estimated Bracket 2 and is useful as a cleaner starting shell rather than as the intended final power target. ([EDHREC](https://edhrec.com/average-decks/rishkar-peema-renegade/upgraded "https://edhrec.com/average-decks/rishkar-peema-renegade/upgraded"))

**Mandatory removals:**

- Ivy Lane Denizen;
- Herd Baloth;
- Seedborn Muse;
- untap or counter engines that restore deterministic loops.

The remaining archetype—counter placement, creatures becoming mana sources, combat growth and ordinary protection—is highly coherent.

### Balmor reference

The current EDHREC aggregate contains 153 user-bracketed Bracket 3 lists. A current public Archidekt list is 100 cards and estimated Bracket 3. ([EDHREC](https://edhrec.com/average-decks/balmor-battlemage-captain/upgraded "https://edhrec.com/average-decks/balmor-battlemage-captain/upgraded"))

**Observed composition risks:**

- Storm-Kiln Artist;
- Veyran and Harmonic Prodigy;
- cost reducers;
- token makers on every spell;
- recursive or untapping spells;
- storm-like finishers.

**Curriculum transformation:** preserve cheap interactive spells and combat-trigger timing, but remove ritual engines, trigger multipliers, storm payoffs and repeatable spell loops.

---

## Matchup reference 4: Adeline vs Florian

### Adeline reference

The current Bracket 3 aggregate has 331 user-bracketed decks. A current public Archidekt list is 100 cards and estimated Bracket 3. ([EDHREC](https://edhrec.com/average-decks/adeline-resplendent-cathar/upgraded "https://edhrec.com/average-decks/adeline-resplendent-cathar/upgraded"))

**Observed composition risks:**

- three Game Changers in the aggregate: Farewell, Smothering Tithe and Teferi’s Protection;
- Cathars’ Crusade creates extensive trigger and counter bookkeeping;
- token doublers enlarge battlefield and combat branching;
- repeated free protection can reduce the value of removal timing;
- white tax pieces can turn the matchup into soft stax.

**Curriculum transformation:** remove all Game Changers, token doublers, Cathars’ Crusade and taxes. Retain Humans, bounded token generation, ordinary interaction and combat-relevant anthems.

### Florian reference

The current Bracket 3 aggregate contains damage, burn, group-slug and mana-explosion cards. A recently crawled 100-card Archidekt list is estimated Bracket 3. ([EDHREC](https://edhrec.com/average-decks/florian-voldaren-scion/upgraded "https://edhrec.com/average-decks/florian-voldaren-scion/upgraded"))

**Observed composition risks:**

- Jeska’s Will;
- Heartless Hidetsugu;
- Mana Geyser;
- Storm-Kiln Artist;
- broad group-slug effects;
- large X-drain spells;
- impulse-draw chains.

**Curriculum transformation:** retain bounded damage and attack-first sequencing. Remove explosive mana, multiplayer-scaled damage, large drain finishers and repeated impulse chains.

---

## Matchup reference 5: Akiri vs Gisa and Geralf

Akiri uses the same references and tuning described above.

For Gisa and Geralf, the current EDHREC Bracket 3 aggregate has 493 user-bracketed decks, and a public 100-card Archidekt list supplies an actual deck reference. ([EDHREC](https://edhrec.com/average-decks/gisa-and-geralf/upgraded "https://edhrec.com/average-decks/gisa-and-geralf/upgraded"))

**Observed composition risks:**

- Gravecrawler;
- Rooftop Storm;
- Ashnod’s Altar;
- Phyrexian Altar;
- Carrion Feeder;
- Buried Alive;
- high trigger density from token and death payoffs.

**Curriculum transformation:** remove every free-sacrifice/free-recast deterministic loop. Preserve ETB mill, normal graveyard casting, ordinary Zombie lords, creature removal, graveyard hate and combat.

---

# Commander-specific learning opportunities

| CommanderCommander-specific lessons |                                                                                                                                                                                                   |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Akiri**                           | Cast before attacks or hold protection; expose commander to removal or delay; reserve mana; choose which Equipment to detach; judge commander-damage lines; manage tax against equip costs        |
| **Chevill**                         | Cast before upkeep or preserve mana; select a bounty target; time removal around protection; decide whether a marked target is still worth killing; manage commander tax versus interaction       |
| **Neyith**                          | Cast before combat; preserve the hybrid combat payment; select the doubled creature; judge forced blocks; distinguish commander value from ordinary fight spells                                  |
| **Balmor**                          | Deploy before a spell chain or wait for protection; decide whether a spell is worth using only for the pump; plan lethal combat around commander removal                                          |
| **Raff**                            | Tap creatures to draw or preserve attackers/blockers; cast Raff before holding up counters; choose when the expensive team-pump ability is worthwhile                                             |
| **Rishkar**                         | Select ETB counter targets; delay commander for better targets or cast for immediate mana; account for tax when the commander is also ramp infrastructure                                         |
| **Florian**                         | Cast before a damage turn; sequence combat and burn before postcombat main; preserve mana to use the exiled card; determine whether recasting is better than using current resources              |
| **Adeline**                         | Cast before combat or retain protection; decide when commander damage matters; manage repeated tax after removal; distinguish commander deployment from overextension                             |
| **Gisa and Geralf**                 | Cast to self-mill immediately or wait for graveyard value; choose whether returning the commander to the command zone sacrifices a potential recursion line; plan once-per-turn graveyard casting |
| **Rashmi**                          | Cast with interaction available; designate and sequence the first spell each turn; protect the value engine; decide whether commander tax is justified in a resource-control game                 |

Akiri and Chevill together cover more of the general Commander-learning contract than any other pair without making either commander the sole executable game plan.

---

# Preconstructed-deck findings

## Reap the Tides — Aesi, Tyrant of Gyre Strait

Wizards published Reap the Tides as an entry-level Commander deck containing 30 creatures, ten sorceries and a landfall/ramp/sea-monster structure. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/feature/commander-legends-commander-decklists-2020-11-05 "https://magic.wizards.com/en/news/feature/commander-legends-commander-decklists-2020-11-05"))

**Advantages:**

- official and stable decklist provenance;
- coherent ramp and landfall concepts;
- straightforward mana;
- clear large-creature endgame.

**ML disadvantages:**

- low interaction density;
- strong commander-driven value snowball;
- many repetitive landfall triggers;
- limited hidden-information play;
- substantial solitaire behavior;
- poor balance against many ordinary creature decks once Aesi remains in play.

**Classification:** useful as a C0 engine/conformance fixture, but **POOR FIRST SERIOUS CURRICULUM**.

## Undead Unleashed — Wilhelt, the Rotcleaver

Wizards’ official Undead Unleashed list documents a coherent Zombie, death-trigger, token and recursion deck. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/innistrad-midnight-hunt-commander-decklists "https://magic.wizards.com/en/news/announcements/innistrad-midnight-hunt-commander-decklists"))

**Advantages:**

- official list;
- coherent typal identity;
- visible combat plan;
- graveyard and sacrifice learning value.

**ML disadvantages:**

- substantial death-trigger ordering;
- decayed tokens;
- sacrifice decisions;
- recursive Zombies;
- Rooftop Storm and related combo risk;
- large token battlefields.

**Classification:** **POOR FIRST CURRICULUM AS-IS**, potentially good later after substantial curation.

## Precon conclusion

No untouched preconstructed deck examined is preferable to the curated finalists. The official provenance is useful, but “preconstructed” does not imply low branching, low commander dependency, or low rules complexity. The current bracket framework also explicitly no longer equates Bracket 2 with precons. ([MAGIC: THE GATHERING](https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025 "https://magic.wizards.com/en/news/announcements/commander-brackets-beta-update-october-21-2025"))

---

# Bracket-based curriculum analysis

## Does B2 → B3 → B4 → B5 make ML sense?

Only approximately.

Official brackets express desired social experience, power, and speed. They do not directly measure:

- legal-action count;
- trigger density;
- hidden-information complexity;
- observation dimensionality;
- episode variance;
- engine implementation difficulty;
- number of mechanically distinct cards;
- multiplayer opponent-selection complexity.

A Bracket 2 token deck can have a much larger combat action space than a Bracket 4 two-card combo deck. A mono-color deck can be harder to simulate than a two-color deck. A precon can contain more engine edge cases than a carefully curated Bracket 3 list.

## Recommended staged curriculum

### C0 — conformance and action-protocol curriculum

**Environment:** curated Bracket 2 vs Bracket 2 or reduced versions of the finalist archetypes.

**Suggested properties:**

- zero Game Changers;
- zero intentional combos;
- zero or one tutor;
- bounded token production;
- mostly ordinary activated, triggered and static abilities;
- reduced interaction suite;
- no exotic replacement or control-changing effects.

**Skills introduced:**

- mulligans;
- lands and mana;
- ordinary spell casting;
- targets;
- attacks and blocks;
- priority;
- commander casting;
- commander tax;
- command-zone choices;
- terminal conditions.

C0 can be skipped as a **policy-learning stage** if Argentum’s action protocol is already proven, but it should not be skipped as an engineering regression suite.

### C1 — first serious fixed-deck curriculum

**Environment:** curated Bracket 3 **Akiri vs Chevill**.

**Skills introduced:**

- meaningful hidden interaction;
- protection and removal timing;
- combat races;
- threat assessment;
- resource reservation;
- commander risk and tax;
- long-horizon battlefield planning;
- asymmetric but fair game plans.

### C2 — Bracket 3 multi-deck and list-generalization curriculum

Expand to approximately six to ten curated decks:

- Akiri;
- Chevill;
- Neyith;
- Raff;
- Rishkar;
- Balmor;
- Florian;
- Adeline;
- tuned Gisa and Geralf;
- eventually Rashmi.

Use multiple legal 100-card variants per commander, controlled card substitutions, and matchup rotation.

**Skills introduced:**

- deck-identity inference;
- unseen-card generalization;
- graveyard and exile zones;
- counterspell inference;
- varying combat structures;
- altered interaction profiles;
- adaptation to different commander dependencies.

### C3 — advanced Commander, split into two branches

#### C3-M: multiplayer Commander

Use three- and four-player Bracket 3/4 environments.

**New skills:**

- target-player selection;
- threat assessment across opponents;
- turn-order planning;
- asymmetric hidden information;
- elimination order;
- kingmaking avoidance;
- temporary alignment;
- opponent modelling;
- nonstationary multi-agent learning.

#### C3-C: Bracket 5/cEDH specialist curriculum

**New skills:**

- aggressive mulligans;
- known-metagame priors;
- compact combo recognition;
- tutor inference;
- free interaction;
- stack wars;
- priority traps;
- tournament-oriented strategic discipline.

These branches should not be treated as a single linear difficulty ladder. Multiplayer fair Commander and cEDH test substantially different capabilities.

---

# Argentum implementation considerations

## Verified architectural advantages

The public repository describes Argentum as a deterministic Kotlin engine with immutable game state and a pure functional API. It exposes turn structure, priority, the stack, combat, triggers, activated and static abilities, state-based actions, targeting, layers, and replacement effects. Its Gym layer exposes reset, step, observations, legal actions, structured pending decisions, fork, snapshot/restore, batch stepping, hidden-information masking, schema hashes and state digests.

These are repository claims and interfaces, not proof that every relevant rule is conformant for every selected card.

## Verified Commander runtime support

Repository code shows real Commander-specific runtime behavior rather than only deck validation:

- commanders begin in or can be cast from the command zone;
- commander tax increases by two for each previous command-zone cast;
- tax count is updated when casting is committed;
- owners can be asked whether a commander moving to specified zones should return to the command zone;
- commander identity is retained across zone changes;
- commander-damage loss checking exists;
- token copies are distinguished from the original commander for commander-damage purposes.

That materially lowers the risk of choosing Commander as the first target.

## Candidate-specific implementation evidence

### Definition present and tested

**Gisa and Geralf**

- card definition present;
- ETB mill implemented;
- filtered Zombie cast from graveyard;
- once-per-turn permission;
- normal cost and timing;
- scenario tests for filtering, spending and next-turn refresh.

### Definition present, conformance not yet proven

**Balmor, Battlemage Captain**

- card definition present;
- generic spell-cast trigger;
- group stat modification;
- temporary trample grant;
- generated file warns that review and scenario testing are still required.

### Exact definitions not located in this repository search

Exact-name searches did not surface verified implementation files for:

- Akiri;
- Chevill;
- Neyith;
- Raff;
- Rishkar;
- Florian;
- Adeline;
- Rashmi.

Some searches surfaced legalities, product coverage, ratings or backlog references rather than executable card definitions. Search absence is not proof of total absence; the eventual audit should query Argentum’s own CardDiscovery/coverage machinery at a pinned commit.

## Burden classification

| CandidateCard definition statusReusable mechanic evidenceLikely burden |                                      |                                                     |                                                                                 |
| ---------------------------------------------------------------------- | ------------------------------------ | --------------------------------------------------- | ------------------------------------------------------------------------------- |
| **Akiri**                                                              | Exact definition not located         | Equipment attach/unattach exists                    | Missing card definition; likely small commander addition; medium 99-card burden |
| **Chevill**                                                            | Exact definition not located         | Counters, targets, death triggers likely general    | Likely medium addition for bounty/LKI condition                                 |
| **Neyith**                                                             | Exact definition not located         | Fight and must-block exist                          | Small–medium addition                                                           |
| **Raff**                                                               | Exact definition not located         | Multiple-creature tap cost exists                   | Small–medium addition                                                           |
| **Rishkar**                                                            | Exact definition not located         | Counter-filtered ability granting exists            | Small–medium addition                                                           |
| **Balmor**                                                             | Definition present, test not located | Generic spell trigger and temporary effects exist   | Simple conformance/test work for commander; medium list burden                  |
| **Florian**                                                            | Exact definition not located         | Life tracking, exile/play permissions require audit | Likely medium reusable addition                                                 |
| **Adeline**                                                            | Exact definition not located         | Combat/token framework exists                       | Medium tapped-and-attacking/attack-target work                                  |
| **Gisa and Geralf**                                                    | Present and scenario-tested          | Graveyard casting permission demonstrated           | Commander low; 99-card list medium                                              |
| **Rashmi**                                                             | Exact definition not located         | Top-card/free-cast primitives require audit         | Medium–large addition                                                           |

## Required terminology

### CARD DEFINITION MISSING

The engine already represents the necessary general rules, but the specific card has not been encoded and tested.

Example candidate: possibly Akiri if attachment, unattachment, conditional draw and temporary indestructible can all be composed from existing primitives.

### REUSABLE MECHANIC MISSING

The card requires a new general rule/effect/condition that should be reusable by other cards.

Example candidate: Chevill’s bounty-marked death event if Argentum cannot currently associate a marker with a later last-known-information death trigger.

### CORE RULE / ENGINE GAP

A foundational behavior is missing or incorrect:

- priority;
- stack resolution;
- zone identity;
- commander tax;
- hidden-information isolation;
- combat legality;
- deterministic shuffle;
- player-choice exposure;
- replacement-effect ordering.

No obvious core Commander foundation gap was found in the preliminary repository inspection, but exact-list conformance could still reveal one.

---

# Finalists

## #1 — Akiri, Fearless Voyager vs Chevill, Bane of Monsters

### Why

- exact desired two-color profile;
- fair board-oriented strategies;
- substantial combat;
- high interaction;
- meaningful hidden information;
- low intended tutor density;
- no required combo;
- no required stax;
- moderate rule complexity;
- excellent protection-versus-removal learning loop;
- both commanders matter without completely defining whether their decks function;
- strong commander tax and recast decisions;
- reusable skills transfer to many Commander archetypes.

### Risks

- Chevill may naturally prey on equipped creatures;
- excessive Golgari removal can suppress Akiri;
- excessive Akiri protection can suppress Chevill;
- Chevill’s bounty trigger requires precise death/LKI behavior;
- Akiri’s exact Argentum definition was not located;
- the matchup has no credible public measured 1v1 win rate.

### Score

**91/100**

---

## #2 — Neyith of the Dire Hunt vs Raff, Weatherlight Stalwart

### Why

- richest combined combat and stack environment;
- fight, forced blocks, tokens, cantrips and counters;
- high sequencing value;
- strong contrast without combo dependency;
- both commanders create repeated structured choices;
- several relevant Argentum primitives already appear to exist.

### Risks

- Neyith may prey too strongly on tokens;
- Raff may counter the commander too efficiently;
- both commanders have high dependency;
- fight chains and token battlefields can expand branching;
- play-first effects may be large.

### Score

**87/100**

---

## #3 — Rishkar, Peema Renegade vs Balmor, Battlemage Captain

### Why

- high mechanical coherence;
- permanent-based versus spell-based development;
- substantial combat;
- meaningful removal targeting;
- one commander definition already exists;
- counter-filtered mana abilities appear compatible with existing engine abstractions;
- episodes should be shorter than the attrition-heavy finalists.

### Risks

- Rishkar’s current aggregate contains an explicit infinite combo;
- Balmor shells easily drift into storm;
- green interaction may be too weak;
- tempo draws may create matchup polarization;
- Balmor’s current definition lacks located scenario coverage.

### Score

**84/100**

---

# Provisional recommendation

## PROVISIONAL FIRST MATCHUP

# **Akiri, Fearless Voyager vs Chevill, Bane of Monsters**

The provisional target is:

> **Curated Bracket 3 Boros Equipment Midrange**
> versus
> **Curated Bracket 3 Golgari Bounty/Deathtouch Interactive Midrange**

This pairing is preferable to the alternatives because it places the highest proportion of game value in general Magic skills rather than archetype-specific tricks:

- board development;
- threat assessment;
- removal;
- protection;
- target selection;
- combat;
- mana reservation;
- sequencing;
- hidden-information reads;
- commander timing;
- commander tax;
- command-zone choices.

It is more interaction-rich than Rishkar–Balmor, less mechanically explosive than Adeline–Florian, less commander-dependent than Neyith–Raff, less zone- and combo-heavy than Akiri–Gisa/Geralf, and substantially more combat-oriented than Rashmi-based pairings.

## Provisional deck-envelope constraints

These are constraints for later deck construction, not final card selections.

### Both decks

- exactly legal 100-card Commander construction;
- current ban-list compliant;
- Bracket 3 intent;
- zero Game Changers initially;
- zero deterministic or infinite combos;
- zero extra-turn cards;
- zero mass land denial;
- zero hard stax pieces;
- zero poison or alternate-win package;
- no repeated universal-tutor loop;
- no more than two conventional sweepers;
- no card chosen solely as a narrow matchup hate piece.

### Akiri envelope

- approximately 24–30 creatures;
- approximately 12–16 Equipment;
- two or three meaningful Equipment tutors at most;
- six to ten interactive/protection spells;
- no Teferi’s Protection;
- no Sunforger toolbox;
- no Equipment-copy package;
- no mass free-equip chain;
- ordinary combat as the primary win condition.

### Chevill envelope

- approximately 24–30 creatures;
- substantial but bounded deathtouch presence;
- approximately nine to twelve targeted interaction spells;
- one or two meaningful tutors at most;
- modest recursion;
- no Demonic Tutor;
- no Fynn/poison;
- no Revel in Riches alternate win;
- no Thornbite Staff pinger engine;
- no heavy reanimation or politics package.

---

# What must be checked in Argentum before locking the matchup

## Format-policy checks

-  Freeze the exact 1v1 starting life total.
-  Freeze whether the starting player draws on turn one.
-  Freeze the mulligan procedure.
-  Freeze random starting-player selection and seed handling.
-  Confirm that official Commander construction and color identity are enforced.
-  Confirm the current banned list is versioned with the environment.
-  Track Game Changers as metadata independently of legality.
-  Record the bracket-policy version in every environment manifest.

## Commander-rule checks

-  Commander begins in the command zone.
-  Casting from the command zone produces an externally controlled action.
-  Tax is exactly two generic mana per prior command-zone cast.
-  Tax interacts correctly with cost increases, reductions and alternate costs.
-  Tax count changes only when the casting process commits correctly.
-  Commander identity persists across every zone change.
-  Graveyard/exile command-zone choice follows the correct state-based-action timing.
-  Hand/library command-zone choice follows the correct replacement behavior.
-  The choice is exposed to the agent and never silently auto-resolved.
-  Commander damage is tracked per original commander.
-  Token copies do not count as the original commander.
-  Control-changing effects do not erase accumulated commander damage.
-  Snapshot, restore and fork preserve tax and commander-damage state.

## Exact card-coverage checks

-  Resolve every card name through executable `CardDefinition` discovery.
-  Do not count legality JSON, Scryfall data, set metadata or a coverage entry as implementation.
-  Require a scenario test for every newly implemented commander.
-  Produce a 200-row coverage matrix: 100 cards × two lists.
-  Classify every gap as card definition, reusable mechanic, or core rule gap.
-  Record which cards use generated definitions without conformance tests.
-  Test every mode, target restriction, optional choice and intervening-if condition.
-  Verify all printed reprints resolve to the same Oracle behavior.

## CandidateCompiler and decision-control checks

-  Complete legal priority actions are externally exposed.
-  Targets are externally controlled.
-  Modes are externally controlled.
-  X values are externally controlled.
-  Alternate and additional costs are externally controlled.
-  Mana payments are externally controlled or exhaustively represented.
-  Equipment attachment targets are externally controlled.
-  Attackers are externally controlled.
-  Blockers are externally controlled.
-  Multiple-blocker ordering is externally controlled.
-  Combat-damage assignment is externally controlled.
-  Trigger ordering is externally controlled where rules permit choice.
-  Replacement-effect ordering is externally controlled.
-  “May” decisions are externally controlled.
-  Search selections and fail-to-find legality are externally controlled.
-  Command-zone replacement decisions are externally controlled.
-  Mulligans and bottom-card selections are externally controlled.
-  No heuristic controller resolves a player decision behind the agent’s observation/action contract.

## Hidden-information checks

-  Opponent hand identities are masked.
-  Library order is masked.
-  Publicly revealed cards remain known after moving to hidden zones only as rules permit.
-  Search and reveal events update known information correctly.
-  State digests do not leak hidden card identities.
-  Legal-action IDs do not leak opponent hidden information.
-  Forked environments preserve the acting player’s information state.

## Akiri-specific checks

-  “Attacks a player with one or more equipped creatures” triggers once, not once per creature.
-  Equipment attachment state is represented exactly.
-  Unattaching is a cost and cannot be reversed if the ability is countered.
-  Only Equipment attached to the relevant controlled creature is eligible.
-  Indestructible duration ends at the correct cleanup point.
-  Destroy, exile, bounce, sacrifice and -X/-X effects interact correctly with protection.
-  Equipment remains on the battlefield after being unattached.
-  Equipping respects sorcery timing unless another effect permits otherwise.
-  Illegal attachment state is repaired correctly by state-based actions.

## Chevill-specific checks

-  Upkeep condition checks whether an opponent controls a valid bounty-marked permanent.
-  Target selection occurs only when the trigger permits it.
-  Only the intended permanent types can receive the counter under current Oracle text.
-  Existing bounty counters suppress new placement correctly.
-  A bounty-marked permanent dying triggers exactly once.
-  Last-known information is used correctly.
-  Exile, bounce and command-zone movement do not count as dying.
-  Control changes before death are handled correctly.
-  Simultaneous deaths and Chevill dying at the same time are rules-correct.
-  Life gain and card draw are optional or mandatory exactly as Oracle text specifies.

## Combat checks

-  First strike and double strike.
-  Deathtouch.
-  Trample.
-  Indestructible.
-  Vigilance.
-  Menace and other blocking restrictions used by the final lists.
-  Multiple blockers.
-  Lethal-damage assignment with deathtouch.
-  Commander damage.
-  Combat tricks after blocks.
-  Creatures removed from combat.
-  Equipment becoming unattached during combat.
-  Simultaneous combat damage and resulting triggers.

## Mana and payment checks

-  Complete enumeration of colored and generic payment choices.
-  Equipment activation and protection costs compete correctly for mana.
-  Mana abilities produce legal candidates without unnecessary priority events.
-  Automatic payment, if retained, is provably lossless for the selected lists.
-  Mana sources are not silently tapped when materially different payment paths exist.
-  Commander tax is represented in the legal-action cost before selection.
-  Snapshot/restore retains mana pools and payment continuations exactly.

## Determinism and replay checks

-  Seeded shuffles reproduce exact library order.
-  Random bottom ordering reproduces exactly where applicable.
-  Candidate order is deterministic or canonically sorted.
-  Candidate IDs remain stable across equivalent states.
-  Observation serialization is canonical.
-  State digests match after replay.
-  Forked children do not mutate parent state.
-  Snapshot→restore yields identical observation, legal candidates and digest.
-  Complete games can be replayed action by action without divergence.

## Performance and balance checks

-  Measure legal-action count by decision type.
-  Measure attacks and block-combination distributions.
-  Measure mean and percentile episode length.
-  Measure decisions per game.
-  Measure fork/step throughput.
-  Measure memory per parallel environment.
-  Detect pass-priority or no-progress loops.
-  Add maximum-turn and maximum-decision diagnostic guards.
-  Run random-vs-random, heuristic-vs-random and heuristic mirror baselines.
-  Run paired seeds with both deck assignments.
-  Swap starting player for every seed.
-  Report confidence intervals rather than only point estimates.
-  Inspect whether removal, commander availability or opening hands dominate outcomes.
-  Adjust lists before changing the learning algorithm.

---

# Future progression

## C0 — Commander conformance slice

**Pair:** simplified Akiri vs simplified Chevill, or two cleaned Bracket 2 shells.

**Purpose:** validate the full observation/action protocol, Commander rules, combat and replay determinism.

**Restrictions:** zero Game Changers, zero combos, minimal tutors, reduced card-mechanic diversity.

## C1 — first serious fixed matchup

**Pair:** curated Bracket 3 Akiri vs Chevill.

**Purpose:** learn fair interactive Commander with meaningful combat, protection, removal, hidden information and commander management.

**Training progression:**

1. scripted or heuristic teachers;
2. optional behavior-cloning bootstrap;
3. recurrent off-policy learning or search-assisted policy improvement;
4. fixed-matchup self-play;
5. exploitability and policy-diversity checks.

The algorithm should remain a separate decision from deck selection.

## C2 — Commander deck generalization

Add Neyith, Raff, Rishkar, Balmor, Florian, Adeline and tuned Gisa/Geralf. Introduce multiple legal list variants and controlled card substitutions.

**Purpose:** move from memorizing two card sets to learning:

- card roles;
- reusable mechanics;
- commander archetypes;
- hidden deck identity;
- matchup adaptation;
- unfamiliar but mechanically related cards.

## C3 — advanced specialization

### C3-M: multiplayer

Three- and four-player Bracket 3/4 games, initially with fixed pods and later randomized pods.

### C3-C: optimized/cEDH

Bracket 4 and then Bracket 5 decks with fast mana, efficient tutors, compact combos, free interaction and stronger mulligan pressure.

These should remain separate evaluation tracks. A policy can become highly competent at cEDH stack wars without becoming competent at multiplayer threat assessment, and vice versa.

---

# Final conclusion

The strongest first serious Commander curriculum is **not** the simplest mono-color creature mirror, a random pair of precons, or a cEDH matchup. It is a curated, same-bracket asymmetric matchup where:

- the battlefield matters;
- combat matters;
- removal and protection matter;
- hidden information matters;
- commanders matter without being the entire deck;
- choices repeat in recognizable forms;
- no single deterministic line dominates training.

Under those criteria, the best provisional target is:

# **Akiri, Fearless Voyager vs Chevill, Bane of Monsters**

The pair should advance to exact-list construction only after the Argentum audit establishes card discovery, mechanic coverage, complete external decision control, deterministic replay, and a paired-seed balance harness.