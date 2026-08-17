package com.wingedsheep.engine.triggers

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.SpellFizzledEvent
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.player.CreaturesDiedThisTurnComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.splice
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.conditions.Condition
import com.wingedsheep.sdk.scripting.conditions.CreatureDiedThisTurnCondition
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.Mode
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import com.wingedsheep.sdk.scripting.targets.TargetPermanent
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.values.ContextPropertyKey
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import kotlinx.serialization.json.Json

/**
 * Synthetic resolution coverage for CR 608.2b.
 *
 * These tests deliberately bypass card definitions and put a locked triggered ability directly
 * on the stack. That keeps the assertions about the generic resolution payload rather than a
 * card-specific authoring path.
 */
class PartialIllegalTargets608Test : FunSpec({

    val targetCount = DynamicAmount.ContextProperty(ContextPropertyKey.TARGET_COUNT)

    fun driver(): GameTestDriver = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.initMirrorMatch(deck = Deck.of("Forest" to 40))
    }

    val dynamicTargetRequirement = TargetCreature(
        count = 2,
        optional = true,
        dynamicMaxCount = DynamicAmount.Count(Player.You, Zone.BATTLEFIELD, GameObjectFilter.Creature)
    )
    val dynamicSlotModal = card("Synthetic 608 Dynamic Modal") {
        manaCost = "{0}"
        typeLine = "Instant"
        spell {
            modal {
                mode("Count creature slots") {
                    target("creatures", dynamicTargetRequirement)
                    target("artifact", TargetPermanent(filter = TargetFilter.Artifact))
                    effect = Effects.GainLife(targetCount)
                }
            }
        }
    }
    val dynamicSlotSplice = card("Synthetic 608 Dynamic Splice") {
        manaCost = "{0}"
        typeLine = "Instant — Arcane"
        splice("{0}")
        spell {
            val creatures = target("creatures", dynamicTargetRequirement)
            target("artifact", TargetPermanent(filter = TargetFilter.Artifact))
            effect = Effects.GainLife(targetCount)
        }
    }
    val dynamicSlotHost = card("Synthetic 608 Dynamic Host") {
        manaCost = "{0}"
        typeLine = "Instant — Arcane"
        spell { effect = Effects.GainLife(1) }
    }

    fun dynamicDriver(): GameTestDriver = driver().also {
        it.registerCards(listOf(dynamicSlotModal, dynamicSlotSplice, dynamicSlotHost))
    }

    fun putTriggeredAbility(
        driver: GameTestDriver,
        effect: com.wingedsheep.sdk.scripting.effects.Effect,
        targets: List<ChosenTarget>,
        targetRequirements: List<TargetRequirement>,
        chosenModes: List<Int> = emptyList(),
        modeTargetsOrdered: List<List<ChosenTarget>> = emptyList(),
        modeTargetRequirements: Map<Int, List<TargetRequirement>> = emptyMap(),
        damageDistribution: Map<com.wingedsheep.sdk.model.EntityId, Int>? = null,
        interveningIf: Condition? = null
    ) {
        val result = StackResolver(driver.cardRegistry).putTriggeredAbility(
            state = driver.state,
            ability = TriggeredAbilityOnStackComponent(
                sourceId = driver.player1,
                sourceName = "Synthetic 608.2b ability",
                controllerId = driver.player1,
                effect = effect,
                description = "Synthetic 608.2b ability",
                damageDistribution = damageDistribution,
                chosenModes = chosenModes,
                modeTargetsOrdered = modeTargetsOrdered,
                modeTargetRequirements = modeTargetRequirements,
                interveningIf = interveningIf
            ),
            targets = targets,
            targetRequirements = targetRequirements
        )
        result.error shouldBe null
        driver.replaceState(result.newState)
    }

    test("608-01: all targets illegal removes the ability without resolving") {
        val driver = driver()
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(1),
            targets = listOf(ChosenTarget.Permanent(target)),
            targetRequirements = listOf(Targets.Creature)
        )
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.stackSize shouldBe 0
    }

    test("608-02: a non-targeted instruction sees only legal surviving targets") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-03: an up-to target requirement also keeps the legal survivor") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2, optional = true))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-04: multiple requirement payloads retain the surviving target count") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(
                TargetCreature(id = "first"),
                TargetCreature(id = "second")
            )
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-05: prechosen mode target payload is partially legal") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val modeRequirement = TargetCreature(count = 2)
        val modal = ModalEffect(
            modes = listOf(
                Mode(
                    effect = Effects.GainLife(targetCount),
                    targetRequirements = listOf(modeRequirement),
                    description = "Gain life for each target"
                )
            ),
            chooseCount = 1
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = modal,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(modeRequirement),
            chosenModes = listOf(0),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second))
            ),
            modeTargetRequirements = mapOf(0 to listOf(modeRequirement))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-05b: an illegal mode target does not suppress its non-targeted sibling") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val requirement = TargetCreature()
        val modal = ModalEffect(
            modes = listOf(
                Mode(
                    effect = Effects.Composite(
                        Effects.Destroy(EffectTarget.ContextTarget(0)),
                        Effects.GainLife(1)
                    ),
                    targetRequirements = listOf(requirement),
                    description = "Destroy a target creature and gain 1 life"
                ),
                Mode(
                    effect = Effects.Destroy(EffectTarget.ContextTarget(0)),
                    targetRequirements = listOf(requirement),
                    description = "Destroy another target creature"
                )
            ),
            chooseCount = 2
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = modal,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(requirement, requirement),
            chosenModes = listOf(0, 1),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(first)),
                listOf(ChosenTarget.Permanent(second))
            ),
            modeTargetRequirements = mapOf(0 to listOf(requirement), 1 to listOf(requirement))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        (second in driver.state.getBattlefield()) shouldBe false
    }

    test("608-06: legal target portions and non-targeted instructions both resolve") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.Composite(
                Effects.Destroy(EffectTarget.ContextTarget(1)),
                Effects.GainLife(1)
            ),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        (second in driver.state.getBattlefield()) shouldBe false
    }

    test("608-07: locked damage distribution is not recomputed after one target leaves") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")

        putTriggeredAbility(
            driver,
            effect = Effects.DividedDamage(total = 2, minTargets = 1, maxTargets = 2),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2)),
            damageDistribution = mapOf(first to 1, second to 1)
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.state.getEntity(second)?.get<DamageComponent>()?.amount shouldBe 1
    }

    test("608-08: the same object may occupy separate requirement instances") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(
                ChosenTarget.Permanent(first),
                ChosenTarget.Permanent(first),
                ChosenTarget.Permanent(second)
            ),
            targetRequirements = listOf(
                TargetCreature(),
                TargetCreature(),
                TargetCreature()
            )
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-09: controller, type, and property changes recheck each locked slot") {
        val driver = driver()
        val controllerChanged = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val typeChanged = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val propertyChanged = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val survivor = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val requirement = TargetCreature(
            count = 4,
            filter = TargetFilter.CreatureYouControl.powerAtLeast(2)
        )

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(
                ChosenTarget.Permanent(controllerChanged),
                ChosenTarget.Permanent(typeChanged),
                ChosenTarget.Permanent(propertyChanged),
                ChosenTarget.Permanent(survivor)
            ),
            targetRequirements = listOf(requirement)
        )
        driver.replaceState(
            driver.state
                .updateEntity(controllerChanged) { it.with(ControllerComponent(driver.player2)) }
                .updateEntity(typeChanged) {
                    it.get<CardComponent>()?.let { card ->
                        it.with(card.copy(typeLine = TypeLine.artifact(), baseStats = null))
                    } ?: it
                }
                .updateEntity(propertyChanged) {
                    it.get<CardComponent>()?.let { card ->
                        it.with(card.copy(baseStats = com.wingedsheep.sdk.model.CreatureStats(0, 2)))
                    } ?: it
                }
        )
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-10: CR 608.2a intervening-if is checked before target legality") {
        val driver = driver()
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(1),
            targets = listOf(ChosenTarget.Permanent(target)),
            targetRequirements = listOf(Targets.Creature),
            interveningIf = CreatureDiedThisTurnCondition
        )
        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.without<CreaturesDiedThisTurnComponent>()
            }
        )
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("608-11: nested target iteration rebases the aligned survivor scope") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")

        putTriggeredAbility(
            driver,
            effect = Effects.TapEachTarget(),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.state.getEntity(second)?.has<TappedComponent>() shouldBe true
    }

    test("608-12: a direct target executor no-ops and the modal tail continues") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val requirement = TargetCreature(count = 2)
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val modal = ModalEffect(
            modes = listOf(
                Mode(
                    effect = Effects.ReturnToHand(EffectTarget.ContextTarget(0)),
                    targetRequirements = listOf(requirement),
                    description = "Return a target"
                ),
                Mode(
                    effect = Effects.GainLife(1),
                    description = "Gain 1 life"
                )
            ),
            chooseCount = 2
        )

        putTriggeredAbility(
            driver,
            effect = modal,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(requirement),
            chosenModes = listOf(0, 1),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
                emptyList()
            ),
            modeTargetRequirements = mapOf(0 to listOf(requirement), 1 to emptyList())
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        driver.state.getZone(com.wingedsheep.engine.state.ZoneKey(driver.player2, com.wingedsheep.sdk.core.Zone.BATTLEFIELD)) shouldContain second
    }

    test("608-13: modal target slots use the cast-time partial counts") {
        val driver = driver()
        val creature = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val artifact = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        driver.replaceState(
            driver.state.updateEntity(artifact) {
                it.get<CardComponent>()?.let { card ->
                    it.with(card.copy(typeLine = TypeLine.artifact(), baseStats = null))
                } ?: it
            }
        )
        val optionalCreature = TargetCreature(count = 2, optional = true)
        val targetArtifact = com.wingedsheep.sdk.scripting.targets.TargetPermanent(
            filter = TargetFilter.Artifact
        )
        val modal = ModalEffect(
            modes = listOf(
                Mode(
                    effect = Effects.GainLife(targetCount),
                    targetRequirements = listOf(optionalCreature, targetArtifact),
                    description = "Count the two target slots"
                )
            ),
            chooseCount = 1
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = modal,
            targets = listOf(ChosenTarget.Permanent(creature), ChosenTarget.Permanent(artifact)),
            targetRequirements = listOf(optionalCreature, targetArtifact),
            chosenModes = listOf(0),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(creature), ChosenTarget.Permanent(artifact))
            ),
            modeTargetRequirements = mapOf(0 to listOf(optionalCreature, targetArtifact))
        )
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 2
    }

    test("608-14: same-controller and same-creature-type relations are rechecked") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val sameController = TargetCreature(count = 2, sameController = true)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(sameController)
        )
        driver.replaceState(
            driver.state.updateEntity(second) { it.with(ControllerComponent(driver.player2)) }
        )
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore

        val driver2 = driver()
        val typedFirst = driver2.putCreatureOnBattlefield(driver2.player1, "Grizzly Bears")
        val typedSecond = driver2.putCreatureOnBattlefield(driver2.player1, "Grizzly Bears")
        val typeBefore = driver2.getLifeTotal(driver2.player1)
        val sameType = TargetCreature(count = 2, sameCreatureType = true)
        putTriggeredAbility(
            driver2,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(typedFirst), ChosenTarget.Permanent(typedSecond)),
            targetRequirements = listOf(sameType)
        )
        driver2.replaceState(
            driver2.state.updateEntity(typedSecond) {
                it.get<CardComponent>()?.let { card ->
                    it.with(card.copy(typeLine = TypeLine.creature(setOf(Subtype("Wizard")))))
                } ?: it
            }
        )
        driver2.bothPass()

        driver2.getLifeTotal(driver2.player1) shouldBe typeBefore
    }

    test("608-15: a card that leaves and returns is an illegal target") {
        val driver = driver()
        val card = driver.putCardInHand(driver.player2, "Forest")
        driver.moveToGraveyard(card)
        val requirement = com.wingedsheep.sdk.scripting.targets.TargetObject(
            filter = TargetFilter.CardInGraveyard
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(1),
            targets = listOf(ChosenTarget.Card(card, driver.player2, Zone.GRAVEYARD)),
            targetRequirements = listOf(requirement)
        )
        val graveyard = ZoneKey(driver.player2, Zone.GRAVEYARD)
        val exile = ZoneKey(driver.player2, Zone.EXILE)
        driver.replaceState(
            driver.state
                .removeFromZone(graveyard, card)
                .addToZone(exile, card)
                .removeFromZone(exile, card)
                .addToZone(graveyard, card)
        )
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
    }

    test("608-16: locked target identity metadata survives state serialization") {
        val driver = driver()
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(1),
            targets = listOf(ChosenTarget.Permanent(target)),
            targetRequirements = listOf(Targets.Creature)
        )

        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val encoded = json.encodeToString(GameState.serializer(), driver.state)
        val decoded = json.decodeFromString(GameState.serializer(), encoded)
        val stackId = driver.state.stack.single()

        decoded.nextObjectIdentityStamp shouldBe driver.state.nextObjectIdentityStamp
        decoded.objectIdentityStamps shouldBe driver.state.objectIdentityStamps
        decoded.getEntity(stackId)?.get<TargetsComponent>()?.targetEntryStamps shouldBe
            driver.state.getEntity(stackId)?.get<TargetsComponent>()?.targetEntryStamps
    }

    test("608-17: a dynamic up-to maximum is locked before resolution") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val requirement = TargetCreature(
            count = 1,
            optional = true,
            dynamicMaxCount = DynamicAmount.Count(Player.You, Zone.BATTLEFIELD, GameObjectFilter.Creature)
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(requirement)
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
    }

    test("608-18: modal dynamic slots stay partitioned at announcement state") {
        val driver = dynamicDriver()
        val first = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val artifact = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        driver.replaceState(
            driver.state.updateEntity(artifact) {
                it.get<CardComponent>()?.let { card ->
                    it.with(card.copy(typeLine = TypeLine.artifact(), baseStats = null))
                } ?: it
            }
        )
        val cardId = driver.putCardInHand(driver.player1, dynamicSlotModal.name)
        val announcementState = driver.state
        val afterCostState = announcementState
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), second)
            .addToZone(ZoneKey(driver.player1, Zone.GRAVEYARD), second)
        val result = StackResolver(driver.cardRegistry).castSpell(
            state = afterCostState,
            cardId = cardId,
            casterId = driver.player1,
            targetLockState = announcementState,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second), ChosenTarget.Permanent(artifact)),
            targetRequirements = listOf(
                dynamicTargetRequirement,
                TargetPermanent(filter = TargetFilter.Artifact)
            ),
            chosenModes = listOf(0),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second), ChosenTarget.Permanent(artifact))
            ),
            modeTargetRequirements = mapOf(
                0 to listOf(dynamicTargetRequirement, TargetPermanent(filter = TargetFilter.Artifact))
            )
        )

        result.error shouldBe null
        val stackCard = result.newState.getEntity(result.newState.stack.single())
        stackCard?.get<SpellOnStackComponent>()?.modeTargetRequirementsOrdered?.single()
            ?.map { it.count } shouldBe listOf(2, 1)
        stackCard?.get<TargetsComponent>()?.targetRequirements?.map { it.count } shouldBe listOf(2, 1)
    }

    test("608-19: splice dynamic slots and slices stay partitioned at announcement state") {
        val driver = dynamicDriver()
        val first = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val artifact = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        driver.replaceState(
            driver.state.updateEntity(artifact) {
                it.get<CardComponent>()?.let { card ->
                    it.with(card.copy(typeLine = TypeLine.artifact(), baseStats = null))
                } ?: it
            }
        )
        val hostId = driver.putCardInHand(driver.player1, dynamicSlotHost.name)
        val announcementState = driver.state
        val afterCostState = announcementState
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), second)
            .addToZone(ZoneKey(driver.player1, Zone.GRAVEYARD), second)
        val result = StackResolver(driver.cardRegistry).castSpell(
            state = afterCostState,
            cardId = hostId,
            casterId = driver.player1,
            targetLockState = announcementState,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second), ChosenTarget.Permanent(artifact)),
            targetRequirements = listOf(
                dynamicTargetRequirement,
                TargetPermanent(filter = TargetFilter.Artifact)
            ),
            splicedCardNames = listOf(dynamicSlotSplice.name)
        )

        result.error shouldBe null
        val stackCard = result.newState.getEntity(result.newState.stack.single())
        stackCard?.get<SpellOnStackComponent>()?.splicedTargetRequirementsOrdered?.single()
            ?.map { it.count } shouldBe listOf(2, 1)
        stackCard?.get<SpellOnStackComponent>()?.splicedTargetsOrdered?.single()?.size shouldBe 3
    }

    test("608-20: an inherited spell copy retains the source target object identity") {
        val driver = driver()
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val requirement = Targets.Creature
        val (sourceId, stateWithSourceId) = driver.state.newEntity()
        val sourceTargets = TargetsComponent.capture(
            driver.state,
            listOf(ChosenTarget.Permanent(target)),
            listOf(requirement)
        )
        val sourceState = stateWithSourceId
            .withEntity(
                sourceId,
                ComponentContainer.of(
                    CardComponent(
                        cardDefinitionId = "Synthetic 608 Copy",
                        name = "Synthetic 608 Copy",
                        manaCost = ManaCost.parse("{0}"),
                        typeLine = TypeLine.instant(),
                        ownerId = driver.player1
                    ),
                    SpellOnStackComponent(casterId = driver.player1),
                    sourceTargets
                )
            )
            .pushToStack(sourceId)

        val battlefield = ZoneKey(driver.player2, Zone.BATTLEFIELD)
        val graveyard = ZoneKey(driver.player2, Zone.GRAVEYARD)
        val returnedTargetState = sourceState
            .removeFromZone(battlefield, target)
            .addToZone(graveyard, target)
            .removeFromZone(graveyard, target)
            .addToZone(battlefield, target)
        val copyResult = StackResolver(driver.cardRegistry).putSpellCopy(
            state = returnedTargetState,
            sourceSpellId = sourceId
        )

        copyResult.error shouldBe null
        val copyId = copyResult.newState.stack.last()
        copyResult.newState.getEntity(copyId)?.get<TargetsComponent>()?.targetEntryStamps shouldBe
            sourceTargets.targetEntryStamps

        val resolution = StackResolver(driver.cardRegistry).resolveTop(copyResult.newState)
        resolution.events.filterIsInstance<SpellFizzledEvent>().size shouldBe 1
    }
})
