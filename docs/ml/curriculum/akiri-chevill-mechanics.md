# ARG-DECK-01 — Akiri/Chevill mechanic inventory

This inventory is an **AUDIT SNAPSHOT** pinned to source
`31853cfe91b52718dc3fb67f159e6267d9c5fcc1` and upstream
`d66a5d7f1b46b0ed8891c34ccfe163d491c4ff3d`. Its support classifications and
risk labels are not silently regenerated after later engine changes.

The primary matrix is the 200-slot Argentum-Friendly pair. The inventory also includes the Ideal pair so that an Ideal-only mechanic is not hidden by a convenience substitution. `tests_found` lists exact card-specific `*ScenarioTest.kt` paths found by source index; `NONE` does not mean no generic engine test exists.

## Post-audit implementation status

ARG-DECK-02 / [PR #5](https://github.com/chrismaghuhn/argentum-engine/pull/5)
subsequently resolved `A8-FEATURE-002` on current fork main. The generic
named-counter/LKI path already existed; PR #5 added the missing
`CounterType.BOUNTY` / `Counters.BOUNTY` vocabulary and independent tests. No
Chevill-specific tracker was required. The exact Chevill `CardDefinition` and
card-specific scenario tests remain outstanding.

The `bounty_counter`, `dies_trigger`, `last_known_information`, and
`marked_permanent` rows below preserve the original pinned audit assessment;
they are not current-main coverage claims.

Deduplicated mechanic labels: **102** across the union of both variants.

| mechanic | cards_using_it | argentum_support | tests_found | risk |
|---|---|---|---|---|
| `activated_ability` | Bounty Hunter; Brass Squire; Mother of Runes; Royal Assassin; Scavenging Ooze; Viridian Longbow; Viscera Seer | PRESENT — activated abilities and payment choices are core SDK vocabulary. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ViridianLongbowScenarioTest.kt | MEDIUM |
| `artifact_enchantment_removal` | Acidic Slime; Disenchant; Loran of the Third Path; Naturalize; Nature's Claim; Outland Liberator // Frenzied Trapbreaker; Reclamation Sage; Wear // Tear | PRESENT — combined artifact/enchantment removal vocabulary exists. | NONE | LOW |
| `artifact_recursion` | Glissa, the Traitor | GENERIC PRESENT — artifact return/recursion primitives exist; selected card coverage varies. | NONE | MEDIUM |
| `artifact_removal` | Abrade; Putrefy | PRESENT — artifact-removal effects exist; exact card coverage varies. | NONE | LOW |
| `attachment_state` | Basilisk Collar; Bonesplitter; Embercleave; Fireshrieker; Lightning Greaves; Loxodon Warhammer; Mask of Memory; Maul of the Skyclaves; O-Naginata; Prowler's Helm; Shadowspear; Swiftfoot Boots; Sword of the Animist; Vulshok Battlegear; Vulshok Morningstar | GENERIC/PARTIAL — source vocabulary or a related definition was found, but selected-card conformance is not established by this audit. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/MaskOfMemoryScenarioTest.kt | MEDIUM |
| `attack_trigger` | Akiri, Fearless Voyager; Armored Skyhunter; Hooded Blightfang; Ohran Frostfang; Outland Liberator // Frenzied Trapbreaker; Reyav, Master Smith; Sun Titan; Sword of the Animist; Wyleth, Soul of Steel | PRESENT — attack triggers are supported; Akiri exact definition is absent. | NONE | MEDIUM |
| `aura` | Open the Armory | GENERIC PRESENT — aura attachment vocabulary exists; selected use is bounded. | NONE | LOW |
| `board_modifier` | Golgari Charm | PRESENT — continuous/temporary modification vocabulary exists. | NONE | MEDIUM |
| `board_wipe` | Blasphemous Act; Damnation; Infest; Languish; Wrath of God | PRESENT — board-wipe effects are core SDK vocabulary. | NONE | MEDIUM |
| `bounty_counter` | Bounty Hunter; Chevill, Bane of Monsters; Termination Facilitator | PARTIAL — generic counters and LKI fields exist, but a named bounty-counter-on-dies/LKI predicate was not demonstrated. | NONE | BLOCKER |
| `card_advantage` | Vraska, Golgari Queen | GENERIC/PARTIAL — source vocabulary or a related definition was found, but selected-card conformance is not established by this audit. | NONE | MEDIUM |
| `card_selection` | Armored Skyhunter; Astor, Bearer of Blades; Eternal Witness; Faithless Looting; Outpost Siege; Reckless Impulse; Thrilling Discovery; Tireless Tracker | PRESENT — selection/choice primitives are used across draw, discard, search and tutor effects. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/EternalWitnessScenarioTest.kt; mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/RecklessImpulseScenarioTest.kt | MEDIUM |
| `cast_trigger` | Sram, Senior Edificer | PRESENT — cast-trigger vocabulary exists. | NONE | MEDIUM |
| `clue_token` | Tireless Tracker | PRESENT — clue/token primitives exist. | NONE | MEDIUM |
| `combat` | Acidic Slime; Ardenn, Intrepid Archaeologist; Armored Skyhunter; Astor, Bearer of Blades; Auriok Steelshaper; Balan, Wandering Knight; Beast Whisperer; Bounty Hunter; Brass Squire; Bruenor Battlehammer; Cankerbloom; Danitha Capashen, Paragon; Elvish Mystic; Eternal Witness; Fervent Champion; Fleshbag Marauder; Garruk's Packleader; Glissa, the Traitor; Harvester of Souls; Hooded Blightfang; Kemba, Kha Regent; Kor Blademaster; Leonin Shikari; Leyline Prowler; Llanowar Elves; Loran of the Third Path; Mirran Crusader; Morbid Opportunist; Mother of Runes; Ohran Frostfang; Outland Liberator // Frenzied Trapbreaker; Plaguecrafter; Puresteel Paladin; Ravenous Chupacabra; Reclamation Sage; Reyav, Master Smith; Royal Assassin; Sakura-Tribe Elder; Scavenging Ooze; Selfless Spirit; Skyclave Apparition; Sram, Senior Edificer; Stone Haven Outfitter; Stoneforge Mystic; Sun Titan; Termination Facilitator; Tireless Tracker; Toski, Bearer of Secrets; Viscera Seer; Wood Elves; Wyleth, Soul of Steel | PRESENT — combat state, attack, block and damage decisions are core engine vocabulary. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/EternalWitnessScenarioTest.kt; mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/HarvesterOfSoulsScenarioTest.kt | MEDIUM |
| `combat_damage_trigger` | Mask of Memory; Toski, Bearer of Secrets | PRESENT — combat damage trigger vocabulary exists. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/MaskOfMemoryScenarioTest.kt | MEDIUM |
| `commander_zone` | Akiri, Fearless Voyager; Chevill, Bane of Monsters | PARTIAL — ARG-02.1 commander-zone replacement/choice conformance is outside this audit base. | NONE | BLOCKER |
| `copy_effect` | Plumb the Forbidden | GENERIC PRESENT — copy vocabulary is present in the source model; no selected card requires it as a win condition. | NONE | MEDIUM |
| `cost_reduction` | Blasphemous Act | PRESENT — cost modification vocabulary exists. | NONE | MEDIUM |
| `creature_entered` | Garruk's Packleader; Guardian Project; Mentor of the Meek; Welcoming Vampire | PRESENT — creature-entry trigger vocabulary exists. | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/WelcomingVampireScenarioTest.kt | MEDIUM |
| `cycling` | Unearth | GENERIC PRESENT — cycling vocabulary exists; selected exact coverage varies. | NONE | LOW |
| `damage` | Abrade; Blasphemous Act; Viridian Longbow | PRESENT — damage effects are core SDK vocabulary. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ViridianLongbowScenarioTest.kt | LOW |
| `deathtouch` | Acidic Slime; Glissa, the Traitor; Hooded Blightfang; Leyline Prowler; Ohran Frostfang; Viridian Longbow | GENERIC PRESENT — keyword support is present; the selected deathtouch package is mostly definition-missing. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ViridianLongbowScenarioTest.kt | MEDIUM |
| `destroy` | Abrupt Decay; Assassin's Trophy; Beast Within; Bounty Hunter; Damnation; Go for the Throat; Maelstrom Pulse; Putrefy; Ravenous Chupacabra; Royal Assassin; Wrath of God | PRESENT — destroy effects are core SDK vocabulary. | NONE | LOW |
| `dies_trigger` | Chevill, Bane of Monsters; Glissa, the Traitor; Harvester of Souls; Moldervine Reclamation; Morbid Opportunist; Stone Haven Outfitter; Termination Facilitator; Undying Malice | GENERIC PRESENT — dies/LKI fields exist; Chevill’s named bounty condition is the gap. | mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/HarvesterOfSoulsScenarioTest.kt; mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/UndyingMaliceScenarioTest.kt | HIGH |
| `discard` | Faithless Looting; Mask of Memory; Thrilling Discovery | PRESENT — discard and selection primitives exist. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/MaskOfMemoryScenarioTest.kt | MEDIUM |
| `double_strike` | Kor Blademaster; Reyav, Master Smith | PRESENT — double-strike keyword support exists. | NONE | LOW |
| `draw` | Akiri, Fearless Voyager; Chevill, Bane of Monsters; Deathreap Ritual; Faithless Looting; Garruk's Packleader; Garruk's Uprising; Guardian Project; Harvester of Souls; Loran of the Third Path; Mask of Memory; Mentor of the Meek; Moldervine Reclamation; Morbid Opportunist; Night's Whisper; Ohran Frostfang; Outpost Siege; Phyrexian Arena; Plumb the Forbidden; Puresteel Paladin; Read the Bones; Reckless Impulse; Sign in Blood; Sram, Senior Edificer; Stone Haven Outfitter; Thrilling Discovery; Toski, Bearer of Secrets; Vraska, Golgari Queen; Welcoming Vampire; Wyleth, Soul of Steel | PRESENT — draw/card-advantage primitives are common; exact tests vary. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/MaskOfMemoryScenarioTest.kt; mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/HarvesterOfSoulsScenarioTest.kt; mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/RecklessImpulseScenarioTest.kt; mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/WelcomingVampireScenarioTest.kt | LOW |
| `edict` | Diabolic Edict; Fleshbag Marauder; Plaguecrafter; Sheoldred's Edict | PRESENT — sacrifice/edict primitives exist; target/choice conformance varies. | mtg-sets/1993-1999/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/DiabolicEdictScenarioTest.kt | MEDIUM |
| `enchantment` | Outpost Siege | PRESENT — enchantment type/attachment vocabulary exists. | NONE | LOW |
| `end_step_trigger` | Deathreap Ritual | PRESENT — end-step trigger vocabulary exists. | NONE | MEDIUM |
| `equip` | Basilisk Collar; Bonesplitter; Embercleave; Fireshrieker; Kor Blademaster; Lightning Greaves; Loxodon Warhammer; Mask of Memory; Maul of the Skyclaves; O-Naginata; Prowler's Helm; Shadowspear; Swiftfoot Boots; Sword of the Animist; Viridian Longbow; Vulshok Battlegear; Vulshok Morningstar | GENERIC/PARTIAL — source vocabulary or a related definition was found, but selected-card conformance is not established by this audit. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/MaskOfMemoryScenarioTest.kt; mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ViridianLongbowScenarioTest.kt | MEDIUM |
| `equip_cost_reduction` | Astor, Bearer of Blades; Auriok Steelshaper; Bruenor Battlehammer; Fervent Champion; Puresteel Paladin | PRESENT — equipment cost modification vocabulary exists. | NONE | MEDIUM |
| `equipment` | Auriok Steelshaper; Basilisk Collar; Bonesplitter; Bruenor Battlehammer; Embercleave; Fireshrieker; Kemba, Kha Regent; Kor Blademaster; Lightning Greaves; Loxodon Warhammer; Mask of Memory; Maul of the Skyclaves; O-Naginata; Open the Armory; Prowler's Helm; Puresteel Paladin; Shadowspear; Sram, Senior Edificer; Steelshaper's Gift; Stoneforge Mystic; Swiftfoot Boots; Sword of the Animist; Viridian Longbow; Vulshok Battlegear; Vulshok Morningstar | GENERIC PRESENT — equipment data and equip primitives exist; card-specific coverage is sparse. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/MaskOfMemoryScenarioTest.kt; mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/ViridianLongbowScenarioTest.kt | MEDIUM |
| `equipment_attachment` | Akiri, Fearless Voyager; Ardenn, Intrepid Archaeologist; Armored Skyhunter; Balan, Wandering Knight; Brass Squire; Leonin Shikari; Wyleth, Soul of Steel | GENERIC PRESENT — attachment/equip primitives exist; Akiri’s exact definition is absent. | NONE | HIGH |
| `equipped_creatures` | Akiri, Fearless Voyager | GENERIC PRESENT — equipped-creature filtering vocabulary exists; Akiri definition still missing. | NONE | HIGH |
| `etb` | Acidic Slime; Astor, Bearer of Blades; Eternal Witness; Fleshbag Marauder; Garruk's Uprising; Loran of the Third Path; Plaguecrafter; Ravenous Chupacabra; Reclamation Sage; Skyclave Apparition; Stoneforge Mystic; Sun Titan; Wood Elves | PRESENT — ETB triggers/effects are common engine vocabulary. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/EternalWitnessScenarioTest.kt | MEDIUM |
| `exile` | Skyclave Apparition; Tear Asunder | PRESENT — exile effects are core SDK vocabulary. | NONE | LOW |
| `exile_until_end` | Reckless Impulse | PRESENT — temporary exile/return vocabulary exists. | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/RecklessImpulseScenarioTest.kt | MEDIUM |
| `fight` | Garruk Relentless // Garruk, the Veil-Cursed | PRESENT — generic fight/bite support is present in selected source. | mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/GarrukRelentlessScenarioTest.kt | MEDIUM |
| `fight_or_bite` | Bite Down | PRESENT — Bite Down has a generated definition and exact scenario-test file; broad conformance remains mixed. | mtg-sets/2024/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/BiteDownScenarioTest.kt | MEDIUM |
| `first_strike` | Fervent Champion; Glissa, the Traitor | PRESENT — first-strike keyword support exists. | NONE | LOW |
| `flashback` | Sevinne's Reclamation | PRESENT — flashback vocabulary exists; selected exact coverage varies. | NONE | MEDIUM |
| `graveyard_exile` | Scavenging Ooze | PRESENT — graveyard exile vocabulary exists. | NONE | LOW |
| `graveyard_recursion` | Eternal Witness; Regrowth; Sevinne's Reclamation; Sun Titan; Undying Malice; Unearth; Victimize | PRESENT — recursion primitives exist; commander-zone and LKI interactions remain separate. | mtg-sets/2003-2007/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/EternalWitnessScenarioTest.kt; mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/UndyingMaliceScenarioTest.kt | MEDIUM |
| `haste` | Fervent Champion | PRESENT — haste keyword support exists. | NONE | LOW |
| `hexproof` | Snakeskin Veil; Tamiyo's Safekeeping | PRESENT — hexproof keyword/effect support exists. | NONE | LOW |
| `indestructible` | Loran's Escape; Tamiyo's Safekeeping; Toski, Bearer of Secrets | PRESENT — indestructible keyword/effect support exists. | NONE | LOW |
| `instant_speed_equip` | Leonin Shikari | PRESENT — instant-speed equip vocabulary exists; selected exact coverage varies. | NONE | MEDIUM |
| `land` | Farseek; Nature's Lore; Rampant Growth; Sakura-Tribe Elder; Wood Elves | PRESENT — land and basic-land data are supported. | NONE | LOW |
| `land_destruction` | Acidic Slime | PRESENT IN CARD TEXT MODEL — no mass-land-denial package is intentionally selected. | NONE | LOW |
| `landfall` | Tireless Tracker | PRESENT — landfall vocabulary exists. | NONE | LOW |
| `last_known_information` | Chevill, Bane of Monsters | GENERIC PRESENT — ZoneChangeEvent/TriggerContext expose LKI data; Chevill-specific use remains unproven. | NONE | HIGH |
| `life_gain` | Chevill, Bane of Monsters; Moldervine Reclamation; Scavenging Ooze | PRESENT — life-gain effects exist. | NONE | LOW |
| `life_gain_opponent` | Nature's Claim | PRESENT — opponent life-gain effects exist. | NONE | LOW |
| `life_loss` | Hooded Blightfang; Phyrexian Arena | PRESENT — life-loss effects exist. | NONE | LOW |
| `lifelink` | Leyline Prowler | PRESENT — lifelink keyword support exists. | NONE | LOW |
| `mana_ability` | Elvish Mystic; Leyline Prowler; Llanowar Elves | PRESENT — mana abilities are core engine vocabulary. | NONE | LOW |
| `mana_acceleration` | Arcane Signet; Boros Signet; Commander's Sphere; Farseek; Fire Diamond; Golgari Signet; Mind Stone; Nature's Lore; Rampant Growth; Talisman of Conviction; Talisman of Resilience; Wayfarer's Bauble | PRESENT — ramp effects and mana sources are supported generically. | NONE | LOW |
| `mana_source` | Barren Moor; Battlefield Forge; Bojuka Bog; Bonders' Enclave; Boros Garrison; Buried Ruin; Clifftop Retreat; Command Tower; Forest; Golgari Rot Farm; Inspiring Vantage; Llanowar Wastes; Mountain; Nurturing Peatland; Overgrown Tomb; Plains; Rugged Prairie; Sacred Foundry; Slayers' Stronghold; Spectator Seating; Sunhome, Fortress of the Legion; Swamp; Tainted Wood; Takenuma, Abandoned Mire; Temple of Malady; Temple of Triumph; War Room; Woodland Cemetery | PRESENT — basic and nonbasic mana sources are supported generically. | NONE | LOW |
| `mana_value_restriction` | Unearth | GENERIC PRESENT — predicate vocabulary exists; exact selected conformance varies. | NONE | MEDIUM |
| `marked_permanent` | Chevill, Bane of Monsters; Termination Facilitator | PARTIAL — counter/target state exists, but the marked-permanent identity across control and zone change needs conformance proof. | NONE | HIGH |
| `mass_removal` | Blasphemous Act; Damnation; Infest; Languish; Wrath of God | GENERIC PRESENT — board-wipe effects exist; selected exact wipe definitions are mostly absent. | NONE | MEDIUM |
| `metalcraft` | Puresteel Paladin | GENERIC PRESENT — metalcraft vocabulary exists; selected exact coverage varies. | NONE | MEDIUM |
| `minus_x_minus_x` | Infest; Languish | GENERIC PRESENT — -X/-X vocabulary exists; exact selected conformance varies. | NONE | MEDIUM |
| `modal_choice` | Boros Charm; Golgari Charm; Outpost Siege; Tear Asunder; Wear // Tear | PRESENT — modal decisions are structured choices. | NONE | MEDIUM |
| `modal_dfc` | Malakir Rebirth // Malakir Mire | PARTIAL — DFC data/transform vocabulary exists; selected DFC definitions are sparse. | NONE | HIGH |
| `morbid` | Deathreap Ritual | GENERIC PRESENT — morbid condition vocabulary exists; selected exact conformance varies. | NONE | MEDIUM |
| `once_per_turn` | Morbid Opportunist; Welcoming Vampire | PRESENT — once-per-turn conditions are generic. | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/WelcomingVampireScenarioTest.kt | MEDIUM |
| `one_or_more_batch` | Akiri, Fearless Voyager | PRESENT — the attack batch trigger primitive is present in current SDK/source. | NONE | LOW |
| `opponent_control` | Chevill, Bane of Monsters | PRESENT — controller predicates are generic; control-change edge cases need tests. | NONE | HIGH |
| `payment` | Mentor of the Meek | PRESENT — costs and mana payment are engine primitives. | NONE | MEDIUM |
| `planeswalker` | Garruk Relentless // Garruk, the Veil-Cursed; Plaguecrafter; Sheoldred's Edict; Vraska, Golgari Queen | PRESENT — planeswalker model exists; DFC/loyalty conformance varies. | mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/GarrukRelentlessScenarioTest.kt | HIGH |
| `plus_one_counter` | Scavenging Ooze; Snakeskin Veil; Stone Haven Outfitter; Undying Malice | PRESENT — counter placement and counter predicates exist. | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/UndyingMaliceScenarioTest.kt | MEDIUM |
| `power_based_damage` | Bite Down | PRESENT — power-based damage predicate vocabulary exists. | mtg-sets/2024/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/BiteDownScenarioTest.kt | MEDIUM |
| `power_condition` | Garruk's Packleader | PRESENT — power predicates are generic. | NONE | MEDIUM |
| `protection` | Mother of Runes | PRESENT — protection/hexproof/indestructible effects exist. | NONE | MEDIUM |
| `put_onto_battlefield` | Stoneforge Mystic | PRESENT — put-onto-battlefield recursion/search primitives exist. | NONE | MEDIUM |
| `ramp_opponent` | Assassin's Trophy | PRESENT — opponent-ramp effects are generic; not a curriculum pillar. | NONE | LOW |
| `removal` | Garruk Relentless // Garruk, the Veil-Cursed; Vraska, Golgari Queen | PRESENT — removal effects are core SDK vocabulary. | mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/GarrukRelentlessScenarioTest.kt | LOW |
| `replacement_choice` | Chaos Warp; Malakir Rebirth // Malakir Mire | PARTIAL — replacement choice is a structured decision surface; commander-zone conformance is pending. | NONE | HIGH |
| `return_from_graveyard` | Malakir Rebirth // Malakir Mire | GENERIC/PARTIAL — source vocabulary or a related definition was found, but selected-card conformance is not established by this audit. | NONE | MEDIUM |
| `sacrifice` | Diabolic Edict; Fleshbag Marauder; Plaguecrafter; Sheoldred's Edict | PRESENT — sacrifice/edict primitives exist. | mtg-sets/1993-1999/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/DiabolicEdictScenarioTest.kt | LOW |
| `sacrifice_as_cost` | Diabolic Intent; Plumb the Forbidden; Sakura-Tribe Elder; Selfless Spirit; Victimize; Viscera Seer; Vraska, Golgari Queen | PRESENT — cost payment supports sacrifice decisions. | NONE | MEDIUM |
| `same_name` | Maelstrom Pulse | PRESENT — name predicates are generic. | NONE | LOW |
| `scry` | Loran's Escape; Viscera Seer | PRESENT — scry primitive exists. | NONE | LOW |
| `search` | Diabolic Intent; Farseek; Nature's Lore; Open the Armory; Rampant Growth; Sakura-Tribe Elder; Steelshaper's Gift; Stoneforge Mystic; Sword of the Animist; Wood Elves | PRESENT — library search primitives exist; selected tutor definitions vary. | NONE | MEDIUM |
| `shuffle` | Chaos Warp; Diabolic Intent; Farseek; Nature's Lore; Open the Armory; Rampant Growth; Sakura-Tribe Elder; Steelshaper's Gift; Sword of the Animist | PRESENT — library shuffle is part of search/zone operations. | NONE | LOW |
| `split_card` | Wear // Tear | PARTIAL — split-card data is available in the model, but the selected Wear // Tear definition is absent. | NONE | MEDIUM |
| `static_ability` | Auriok Steelshaper; Garruk's Uprising; Vulshok Battlegear; Vulshok Morningstar | PRESENT — static ability vocabulary exists. | NONE | LOW |
| `tapped_target` | Royal Assassin | GENERIC/PARTIAL — source vocabulary or a related definition was found, but selected-card conformance is not established by this audit. | NONE | MEDIUM |
| `target_selection` | Abrade; Abrupt Decay; Assassin's Trophy; Beast Within; Bite Down; Boros Charm; Bounty Hunter; Chaos Warp; Chevill, Bane of Monsters; Diabolic Edict; Disenchant; Generous Gift; Go for the Throat; Maelstrom Pulse; Naturalize; Nature's Claim; Path to Exile; Putrefy; Ravenous Chupacabra; Regrowth; Sevinne's Reclamation; Sheoldred's Edict; Skyclave Apparition; Swords to Plowshares; Tear Asunder; Victimize; Wear // Tear | PRESENT — target predicates and target choices are present; exact card coverage varies. | mtg-sets/1993-1999/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/DiabolicEdictScenarioTest.kt; mtg-sets/2024/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/BiteDownScenarioTest.kt | MEDIUM |
| `targeted_protection` | Boros Charm; Golgari Charm; Loran's Escape; Malakir Rebirth // Malakir Mire; Snakeskin Veil; Tamiyo's Safekeeping; Undying Malice | PRESENT — targeted protection choices exist. | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/UndyingMaliceScenarioTest.kt | MEDIUM |
| `targeted_removal` | Abrade; Abrupt Decay; Assassin's Trophy; Beast Within; Bite Down; Chaos Warp; Diabolic Edict; Disenchant; Generous Gift; Go for the Throat; Maelstrom Pulse; Naturalize; Nature's Claim; Path to Exile; Putrefy; Sheoldred's Edict; Swords to Plowshares; Tear Asunder; Wear // Tear | PRESENT — targeted removal primitives are common; many exact card definitions are absent. | mtg-sets/1993-1999/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/DiabolicEdictScenarioTest.kt; mtg-sets/2024/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/BiteDownScenarioTest.kt | MEDIUM |
| `temporary_ability` | Undying Malice | PRESENT — temporary duration vocabulary exists. | mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/UndyingMaliceScenarioTest.kt | MEDIUM |
| `temporary_indestructible` | Akiri, Fearless Voyager; Boros Charm; Selfless Spirit | GENERIC PRESENT — duration/temporary ability vocabulary exists; Akiri integration is untested. | NONE | MEDIUM |
| `token_creation` | Beast Within; Garruk Relentless // Garruk, the Veil-Cursed; Kemba, Kha Regent | PRESENT — token creation primitives exist. | mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/GarrukRelentlessScenarioTest.kt | MEDIUM |
| `trample` | Garruk's Uprising; Wyleth, Soul of Steel | PRESENT — trample keyword support exists. | NONE | LOW |
| `transform` | Garruk Relentless // Garruk, the Veil-Cursed; Outland Liberator // Frenzied Trapbreaker | PARTIAL — transform/DFC vocabulary exists; selected-card behavior needs scenarios. | mtg-sets/2008-2016/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/GarrukRelentlessScenarioTest.kt | HIGH |
| `unattach_as_cost` | Akiri, Fearless Voyager | PARTIAL — cost vocabulary exists, but no selected exact card definition proves the needed Akiri path. | NONE | HIGH |
| `upkeep_trigger` | Ardenn, Intrepid Archaeologist; Chevill, Bane of Monsters; Kemba, Kha Regent; Outpost Siege; Phyrexian Arena | PRESENT — upkeep triggers are supported; Chevill exact definition is absent. | NONE | HIGH |
| `zone_change` | Malakir Rebirth // Malakir Mire | GENERIC/PARTIAL — source vocabulary or a related definition was found, but selected-card conformance is not established by this audit. | NONE | MEDIUM |

## High-level findings

- The broad engine vocabulary is stronger than the exact card corpus: many keyword/effect families are present, while exact selected CardDefinitions and card-specific tests are sparse.
- The two commander-specific risks are not interchangeable in the pinned snapshot: Akiri is primarily an exact definition gap; Chevill was assessed as needing a reusable named-counter/LKI predicate in addition to its missing exact definition.
- Current-main status: the reusable named-counter/LKI predicate is resolved by ARG-DECK-02 / PR #5; Chevill's exact definition and scenario coverage remain open.
- `commander_zone` is listed once as a foundational blocker. It is not counted once per card and is not implemented by this milestone.
- `Viridian Longbow` is executable and scenario-tested, but the deathtouch-plus-activated-damage package is a structural review item rather than an infinite-combo claim.

## Variant delta

- Argentum-Friendly removes the Ideal-only `split_card`, `modal_dfc`, `return_from_graveyard`, `zone_change`, and `card_advantage` labels from the replaced slots; it does not remove the main combat, equipment, bounty, deathtouch, removal, or target-selection families.
- This is a burden reduction, not a claim that the Friendly definitions are fully tested.
