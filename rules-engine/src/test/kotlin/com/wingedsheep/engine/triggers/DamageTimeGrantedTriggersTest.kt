package com.wingedsheep.engine.triggers

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.core.DamageRecipientKindSet
import com.wingedsheep.engine.core.Outcome
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.event.AbilityRegistry
import com.wingedsheep.engine.event.BattlefieldStaticsIndex
import com.wingedsheep.engine.event.DamageTriggerDetector
import com.wingedsheep.engine.event.GrantedTriggeredAbility
import com.wingedsheep.engine.event.PendingTrigger
import com.wingedsheep.engine.event.TriggerAbilityResolver
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.event.TriggerMatcher
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.DamageUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Filters
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.grantedTriggeredAbility
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantTriggeredAbility
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.events.Recipient
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/**
 * A triggered ability *granted* to a permanent — "target creature gains 'Whenever this creature
 * deals damage to a creature, destroy that creature' until end of turn" (Cruel Deceiver, Commando
 * Raid), or "equipped/enchanted creature has '…'" (Ceremonial Knife, Curious Inquiry) — is still one
 * of its abilities when the permanent dies to the very damage that triggers it.
 *
 * A damage trigger is checked against the objects as they exist immediately after the damage event
 * (CR 603.10). Combat damage is dealt simultaneously (CR 510.2), and the lethal-damage state-based
 * action only runs when a player would next receive priority (CR 704.3), so at that instant the
 * creature is still on the battlefield with its grants — and still equipped or enchanted: the same
 * state-based actions unattach its Equipment (CR 704.5n) and bury its Auras (CR 704.5m) only
 * afterwards. The engine detects triggers after the state-based actions, so it reads a departed
 * object's abilities from the snapshot captured when the damage was dealt — never from the live
 * entity, whose id may already name a newer object (CR 400.7).
 */
class DamageTimeGrantedTriggersTest : FunSpec({

    // "Target creature gains 'Whenever this creature deals damage to a creature, destroy that
    // creature' until end of turn." — Cruel Deceiver's grant, put on a spell.
    val venom = card("Granted Venom") {
        manaCost = "{0}"
        typeLine = "Instant"
        spell {
            val creature = target(TargetFilter(GameObjectFilter.Creature))
            effect = Effects.GrantTriggeredAbility(
                ability = grantedTriggeredAbility {
                    trigger = Triggers.self.dealsDamage(Recipient.AnyCreature)
                    effect = Effects.Destroy(EffectTarget.TriggeringEntity)
                },
                target = creature,
            )
        }
    }

    // "Target creature gains 'Whenever this creature is dealt damage, you gain 3 life' until end of turn."
    val martyrdom = card("Granted Martyrdom") {
        manaCost = "{0}"
        typeLine = "Instant"
        spell {
            val creature = target(TargetFilter(GameObjectFilter.Creature))
            effect = Effects.GrantTriggeredAbility(
                ability = grantedTriggeredAbility {
                    trigger = Triggers.self.isDealtDamage()
                    effect = Effects.GainLife(3)
                },
                target = creature,
            )
        }
    }

    // "Target creature deals 2 damage to another target creature. Return the first creature to its
    // owner's hand." — the damage source leaves within the resolution that dealt the damage.
    val pummel = card("Pummel and Retreat") {
        manaCost = "{0}"
        typeLine = "Instant"
        spell {
            val striker = target(TargetFilter(GameObjectFilter.Creature))
            val victim = target(TargetFilter(GameObjectFilter.Creature))
            effect = Effects.DealDamage(2, victim, damageSource = striker) then Effects.ReturnToHand(striker)
        }
    }

    // "Deal 2 damage to target creature. Return that creature to its owner's hand." — the damaged
    // creature leaves within the resolution that dealt the damage.
    val scald = card("Scald and Scatter") {
        manaCost = "{0}"
        typeLine = "Instant"
        spell {
            val creature = target(TargetFilter(GameObjectFilter.Creature))
            effect = Effects.DealDamage(2, creature) then Effects.ReturnToHand(creature)
        }
    }

    // "Equipped creature has 'Whenever this creature deals combat damage, you gain 3 life.' Equip {0}"
    val blade = card("Lifebond Blade") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        staticAbility {
            ability = GrantTriggeredAbility(
                ability = TriggeredAbility.create(
                    trigger = Triggers.self.dealsCombatDamage(),
                    effect = Effects.GainLife(3),
                ),
                filter = Filters.EquippedCreature,
            )
        }
        equipAbility("{0}")
    }

    // "Enchant creature. Enchanted creature has 'Whenever this creature is dealt damage, you gain 3 life.'"
    val mantle = card("Martyr's Mantle") {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        auraTarget = TargetObject(filter = TargetFilter.Creature)
        staticAbility {
            ability = GrantTriggeredAbility(
                ability = TriggeredAbility.create(
                    trigger = Triggers.self.isDealtDamage(),
                    effect = Effects.GainLife(3),
                ),
                filter = Filters.EnchantedCreature,
            )
        }
    }

    val striker = card("Frail Striker") {
        manaCost = "{0}"
        typeLine = "Creature — Spirit"
        power = 2
        toughness = 1
    }

    val wall = card("Patient Wall") {
        manaCost = "{0}"
        typeLine = "Creature — Wall"
        power = 0
        toughness = 4
    }

    fun driver(): GameTestDriver {
        val d = GameTestDriver()
        d.registerCards(TestCards.all + listOf(venom, martyrdom, pummel, scald, blade, mantle, striker, wall))
        d.initMirrorMatch(deck = Deck.of("Plains" to 40), startingPlayer = 0)
        d.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return d
    }

    fun GameTestDriver.castAndResolve(name: String, targets: List<EntityId>) {
        castSpell(player1, putCardInHand(player1, name), targets).error shouldBe null
        var guard = 0
        while (state.stack.isNotEmpty() && guard++ < 10) bothPass()
    }

    fun GameTestDriver.equip(equipment: EntityId, creature: EntityId) {
        submit(
            ActivateAbility(
                playerId = player1,
                sourceId = equipment,
                abilityId = blade.activatedAbilities.first().id,
                targets = listOf(ChosenTarget.Permanent(creature)),
            )
        ).outcome shouldBe Outcome.Done
        bothPass()
    }

    fun GameTestDriver.attackInto(attacker: EntityId, blocker: EntityId) {
        passPriorityUntil(Step.DECLARE_ATTACKERS)
        declareAttackers(player1, listOf(attacker), player2)
        bothPass()
        declareBlockers(player2, mapOf(blocker to listOf(attacker)))
        passPriorityUntil(Step.END_COMBAT)
    }

    context("through the real combat and damage pipeline") {

        test("a source that dies to the combat damage exchange still fires its granted damage trigger") {
            val d = driver()
            val frail = d.putCreatureOnBattlefield(d.player1, "Frail Striker")
            d.removeSummoningSickness(frail)
            val courser = d.putCreatureOnBattlefield(d.player2, "Centaur Courser")
            d.castAndResolve("Granted Venom", listOf(frail))

            d.attackInto(frail, courser)

            withClue("the 3/3 took only 2 damage, so only the granted trigger can have destroyed it") {
                d.getGraveyard(d.player2).contains(courser) shouldBe true
            }
            withClue("the 2/1 died to the 3 damage dealt back in the same step") {
                d.getGraveyard(d.player1).contains(frail) shouldBe true
            }
        }

        test("a source that survives fires the same granted trigger from its live abilities") {
            val d = driver()
            val frail = d.putCreatureOnBattlefield(d.player1, "Frail Striker")
            d.removeSummoningSickness(frail)
            val patientWall = d.putCreatureOnBattlefield(d.player2, "Patient Wall")
            d.castAndResolve("Granted Venom", listOf(frail))

            d.attackInto(frail, patientWall)

            withClue("the 0/4 took 2 damage and was destroyed by the granted trigger") {
                d.getGraveyard(d.player2).contains(patientWall) shouldBe true
            }
            withClue("a 0-power blocker dealt no damage back") {
                d.findPermanent(d.player1, "Frail Striker") shouldBe frail
            }
        }

        test("a recipient that dies to combat damage still fires its granted dealt-damage trigger") {
            val d = driver()
            val frail = d.putCreatureOnBattlefield(d.player1, "Frail Striker")
            d.removeSummoningSickness(frail)
            val courser = d.putCreatureOnBattlefield(d.player2, "Centaur Courser")
            d.castAndResolve("Granted Martyrdom", listOf(frail))
            val lifeBefore = d.getLifeTotal(d.player1)

            d.attackInto(frail, courser)

            withClue("the 2/1 died to the Courser's 3 damage") {
                d.getGraveyard(d.player1).contains(frail) shouldBe true
            }
            withClue("the grant it had when the damage was dealt still triggered") {
                d.getLifeTotal(d.player1) shouldBe lifeBefore + 3
            }
        }

        // Combat damage is followed by its state-based actions before triggers are detected, but a
        // resolving spell's damage is detected right after the resolution — so these two leave the
        // battlefield inside the same resolution that dealt the damage.

        test("a source that leaves during the damaging resolution still fires its granted damage trigger") {
            val d = driver()
            val frail = d.putCreatureOnBattlefield(d.player1, "Frail Striker")
            val courser = d.putCreatureOnBattlefield(d.player2, "Centaur Courser")
            d.castAndResolve("Granted Venom", listOf(frail))

            d.castAndResolve("Pummel and Retreat", listOf(frail, courser))

            withClue("the striker went back to its owner's hand") {
                d.getHand(d.player1).contains(frail) shouldBe true
            }
            withClue("the 3/3 took only 2 damage, so only the granted trigger can have destroyed it") {
                d.getGraveyard(d.player2).contains(courser) shouldBe true
            }
        }

        test("a recipient that leaves during the damaging resolution still fires its granted dealt-damage trigger") {
            val d = driver()
            val courser = d.putCreatureOnBattlefield(d.player1, "Centaur Courser")
            d.castAndResolve("Granted Martyrdom", listOf(courser))
            val lifeBefore = d.getLifeTotal(d.player1)

            d.castAndResolve("Scald and Scatter", listOf(courser))

            withClue("the Courser went back to its owner's hand") {
                d.getHand(d.player1).contains(courser) shouldBe true
            }
            withClue("the grant it had when the damage was dealt still triggered") {
                d.getLifeTotal(d.player1) shouldBe lifeBefore + 3
            }
        }
    }

    context("granted by an Equipment or an Aura") {

        test("an equipped creature that dies to the combat damage exchange still fires its granted trigger") {
            val d = driver()
            val frail = d.putCreatureOnBattlefield(d.player1, "Frail Striker")
            d.removeSummoningSickness(frail)
            val courser = d.putCreatureOnBattlefield(d.player2, "Centaur Courser")
            val lifebondBlade = d.putPermanentOnBattlefield(d.player1, "Lifebond Blade")
            d.equip(lifebondBlade, frail)
            val lifeBefore = d.getLifeTotal(d.player1)

            d.attackInto(frail, courser)

            withClue("the 2/1 died to the Courser's 3 damage, and the Blade stayed behind unattached") {
                d.getGraveyard(d.player1).contains(frail) shouldBe true
                d.findPermanent(d.player1, "Lifebond Blade") shouldBe lifebondBlade
            }
            withClue("the creature was still equipped when it dealt its combat damage") {
                d.getLifeTotal(d.player1) shouldBe lifeBefore + 3
            }
        }

        test("an enchanted creature that dies to combat damage still fires its granted trigger") {
            val d = driver()
            val frail = d.putCreatureOnBattlefield(d.player1, "Frail Striker")
            d.removeSummoningSickness(frail)
            val courser = d.putCreatureOnBattlefield(d.player2, "Centaur Courser")
            d.castAndResolve("Martyr's Mantle", listOf(frail))
            val lifeBefore = d.getLifeTotal(d.player1)

            d.attackInto(frail, courser)

            withClue("the 2/1 died, and its Aura went to the graveyard with it") {
                d.getGraveyard(d.player1).contains(frail) shouldBe true
                d.getGraveyardCardNames(d.player1).contains("Martyr's Mantle") shouldBe true
            }
            withClue("the creature was still enchanted when it was dealt the damage") {
                d.getLifeTotal(d.player1) shouldBe lifeBefore + 3
            }
        }
    }

    context("from the damage-time snapshot") {
        // Pure data below: one registry-less evaluator graph serves every matcher and detector.
        val predicateEvaluator = PredicateEvaluator(cardRegistry = null)
        val conditionEvaluator = predicateEvaluator.conditions

        val controllerId = EntityId("granted-damage-controller")
        val sourceId = EntityId("granted-damage-source")
        val recipientId = EntityId("granted-damage-recipient")

        val dealsDamage = EventPattern.DealsDamageEvent(recipient = Recipient.AnyCreature)

        fun ability(id: String, trigger: EventPattern) = TriggeredAbility(
            id = AbilityId(id),
            trigger = trigger,
            binding = TriggerBinding.SELF,
            effect = Effects.DrawCards(1),
        )

        val grantedVenom = ability("granted-venom", dealsDamage)

        fun creatureCard(definitionId: String, name: String) = CardComponent(
            cardDefinitionId = definitionId,
            name = name,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 1),
        )

        // The inline "Lifebond Blade" Equipment, as a card on the battlefield.
        val bladeRegistry = CardRegistry().apply { register(blade) }
        val bladeGrant = (blade.staticAbilities.single() as GrantTriggeredAbility).ability
        fun bladeCard() = CardComponent(
            cardDefinitionId = "Lifebond Blade",
            name = "Lifebond Blade",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.ARTIFACT), subtypes = setOf(Subtype("Equipment"))),
        )

        fun sourceAtDamage(stamp: Long, grants: List<TriggeredAbility>) = EntitySnapshot(
            entityId = sourceId,
            name = "Granted Source",
            cardDefinitionId = "granted-damage-source",
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = stamp,
            grantedTriggeredAbilities = grants,
        )

        fun recipientAtDamage(stamp: Long, grants: List<TriggeredAbility> = emptyList()) = EntitySnapshot(
            entityId = recipientId,
            name = "Granted Recipient",
            cardDefinitionId = "granted-damage-recipient",
            controllerId = controllerId,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            battlefieldEntryTimestamp = stamp,
            grantedTriggeredAbilities = grants,
        )

        fun damageEvent(
            source: EntitySnapshot = sourceAtDamage(stamp = 1L, grants = emptyList()),
            recipient: EntitySnapshot = recipientAtDamage(stamp = 2L),
        ) = DamageDealtEvent(
            sourceId = sourceId,
            targetId = recipientId,
            amount = 3,
            isCombatDamage = true,
            recipientKind = DamageRecipientKind.CREATURE,
            recipientKinds = DamageRecipientKindSet.CREATURE,
            damageSourceLastKnownSnapshot = source,
            damageRecipientLastKnownSnapshot = recipient,
        )

        fun damageDetector() = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry(), predicateEvaluator),
            TriggerMatcher(predicateEvaluator, conditionEvaluator),
            predicateEvaluator,
        )

        test("the capture freezes the object's own in-duration grants and nobody else's") {
            val bystanderId = EntityId("granted-damage-bystander")
            val bladeId = EntityId("granted-damage-blade")
            val strayBladeId = EntityId("granted-damage-stray-blade")
            val bystanderGrant = ability("bystander-grant", dealsDamage)
            val endedGrant = ability("ended-grant", dealsDamage)
            val state = GameState(
                zones = mapOf(
                    ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(sourceId, bystanderId, bladeId, strayBladeId),
                ),
                turnOrder = listOf(controllerId),
                grantedTriggeredAbilities = listOf(
                    GrantedTriggeredAbility(sourceId, grantedVenom, Duration.EndOfTurn),
                    GrantedTriggeredAbility(bystanderId, bystanderGrant, Duration.EndOfTurn),
                    // "For as long as <granter> remains on the battlefield" — and the granter is gone.
                    GrantedTriggeredAbility(
                        sourceId,
                        endedGrant,
                        Duration.WhileSourceOnBattlefield(),
                        sourceId = EntityId("granted-damage-departed-granter"),
                    ),
                ),
            )
                .withEntity(
                    sourceId,
                    ComponentContainer.of(
                        creatureCard("granted-damage-source", "Granted Source"),
                        ControllerComponent(controllerId),
                        BattlefieldEntryTimestampComponent(10L),
                        // The second id is a stray reverse-index entry: that Blade is attached elsewhere.
                        AttachmentsComponent(listOf(bladeId, strayBladeId)),
                    ),
                )
                .withEntity(
                    bystanderId,
                    ComponentContainer.of(
                        creatureCard("granted-damage-bystander", "Bystander"),
                        ControllerComponent(controllerId),
                        BattlefieldEntryTimestampComponent(11L),
                    ),
                )
                .withEntity(
                    bladeId,
                    ComponentContainer.of(
                        bladeCard(),
                        ControllerComponent(controllerId),
                        BattlefieldEntryTimestampComponent(12L),
                        AttachedToComponent(sourceId),
                    ),
                )
                .withEntity(
                    strayBladeId,
                    ComponentContainer.of(
                        bladeCard(),
                        ControllerComponent(controllerId),
                        BattlefieldEntryTimestampComponent(13L),
                        AttachedToComponent(bystanderId),
                    ),
                )

            val snapshot = DamageUtils.captureDamageRoleSnapshot(state, sourceId, bladeRegistry, conditionEvaluator)

            withClue("its own in-duration effect grant, then the grant of the Blade attached to it") {
                snapshot?.grantedTriggeredAbilities shouldBe listOf(grantedVenom, bladeGrant)
            }
            withClue("the identity snapshot other callers take carries no grants") {
                DamageUtils.captureDamageEntitySnapshot(state, sourceId)?.grantedTriggeredAbilities shouldBe emptyList()
            }
        }

        test("a departed source fires the grants on its damage-time snapshot") {
            val triggers = mutableListOf<PendingTrigger>()

            damageDetector().detectDamageSourceTriggers(
                state = GameState(),
                statics = BattlefieldStaticsIndex.EMPTY,
                event = damageEvent(source = sourceAtDamage(stamp = 20L, grants = listOf(grantedVenom))),
                triggers = triggers,
                projected = GameState().projectedState,
            )

            triggers.map { it.ability } shouldContainExactly listOf(grantedVenom)
            triggers.single().sourceName shouldBe "Granted Source"
            triggers.single().controllerId shouldBe controllerId
        }

        test("a departed recipient fires the grants on its damage-time snapshot") {
            val grantedMartyrdom = TriggeredAbility(
                id = AbilityId("granted-martyrdom"),
                trigger = EventPattern.DamageReceivedEvent(source = GameObjectFilter.Any),
                binding = TriggerBinding.SELF,
                effect = Effects.GainLife(3),
            )
            val triggers = mutableListOf<PendingTrigger>()

            damageDetector().detectDamageReceivedTriggers(
                state = GameState(),
                statics = BattlefieldStaticsIndex.EMPTY,
                event = damageEvent(recipient = recipientAtDamage(stamp = 40L, grants = listOf(grantedMartyrdom))),
                triggers = triggers,
            )

            triggers.map { it.ability } shouldContainExactly listOf(grantedMartyrdom)
            triggers.single().sourceId shouldBe recipientId
        }

        test("a newer object with the same id never contributes its own grants") {
            val replacementControllerId = EntityId("granted-damage-replacement-controller")
            val replacementGrant = ability("replacement-grant", dealsDamage)
            // The id now names a newer object (a different entry stamp) that carries a grant of its
            // own, stored on the shared id exactly as a stale grant of the old object would be.
            val state = GameState(
                zones = mapOf(ZoneKey(replacementControllerId, Zone.BATTLEFIELD) to listOf(sourceId)),
                turnOrder = listOf(controllerId, replacementControllerId),
                grantedTriggeredAbilities = listOf(
                    GrantedTriggeredAbility(sourceId, replacementGrant, Duration.EndOfTurn),
                ),
            ).withEntity(
                sourceId,
                ComponentContainer.of(
                    creatureCard("granted-damage-replacement", "Replacement"),
                    ControllerComponent(replacementControllerId),
                    BattlefieldEntryTimestampComponent(31L),
                ),
            )
            val detector = TriggerDetector(CardRegistry(), AbilityRegistry(), predicateEvaluator, conditionEvaluator)

            withClue("the old object had no grant, so nothing fires") {
                detector.detectTriggers(
                    state,
                    listOf(damageEvent(source = sourceAtDamage(stamp = 30L, grants = emptyList()))),
                ).shouldBeEmpty()
            }
            withClue("the old object's grant fires — only that one, under the old controller") {
                val triggers = detector.detectTriggers(
                    state,
                    listOf(damageEvent(source = sourceAtDamage(stamp = 30L, grants = listOf(grantedVenom)))),
                )
                triggers.map { it.ability } shouldContainExactly listOf(grantedVenom)
                triggers.single().controllerId shouldBe controllerId
            }
        }

        test("a newer object's Equipment never contributes its grant either") {
            val replacementControllerId = EntityId("granted-damage-replacement-controller")
            val bladeId = EntityId("granted-damage-replacement-blade")
            // The id now names a newer object that is equipped with the Blade right now.
            val state = GameState(
                zones = mapOf(ZoneKey(replacementControllerId, Zone.BATTLEFIELD) to listOf(sourceId, bladeId)),
                turnOrder = listOf(controllerId, replacementControllerId),
            )
                .withEntity(
                    sourceId,
                    ComponentContainer.of(
                        creatureCard("granted-damage-replacement", "Replacement"),
                        ControllerComponent(replacementControllerId),
                        BattlefieldEntryTimestampComponent(31L),
                        AttachmentsComponent(listOf(bladeId)),
                    ),
                )
                .withEntity(
                    bladeId,
                    ComponentContainer.of(
                        bladeCard(),
                        ControllerComponent(replacementControllerId),
                        BattlefieldEntryTimestampComponent(32L),
                        AttachedToComponent(sourceId),
                    ),
                )

            withClue("the newer object does have the Blade's grant right now") {
                TriggerAbilityResolver(bladeRegistry, AbilityRegistry(), predicateEvaluator)
                    .getTriggeredAbilities(sourceId, "granted-damage-replacement", state)
                    .shouldContain(bladeGrant)
            }
            withClue("but the departed object had no grant when it dealt the damage, so nothing fires") {
                TriggerDetector(bladeRegistry, AbilityRegistry(), predicateEvaluator, conditionEvaluator)
                    .detectTriggers(
                        state,
                        listOf(damageEvent(source = sourceAtDamage(stamp = 30L, grants = emptyList()))),
                    )
                    .shouldBeEmpty()
            }
        }

        test("a snapshot without grants serializes as before, and one with grants round-trips") {
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }
            val bare = sourceAtDamage(stamp = 50L, grants = emptyList())
            val granted = bare.copy(grantedTriggeredAbilities = listOf(grantedVenom))

            json.encodeToString(EntitySnapshot.serializer(), bare) shouldNotContain "grantedTriggeredAbilities"
            json.decodeFromString(
                EntitySnapshot.serializer(),
                json.encodeToString(EntitySnapshot.serializer(), granted),
            ) shouldBe granted
        }
    }
})
