package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AlternativeCostType
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.StormCopyTargetContinuation
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.effects.ConditionalEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

/**
 * Characterization for a generic resolving-spell copy payload; this test was introduced RED
 * before the production payload existed.
 *
 * The source spell leaves the stack before its resolution-time may-copy decision resumes. The
 * copied spell must still retain the original effect and target requirements so that retargeting
 * is an explicit player decision rather than an implicit choice or a no-target copy.
 */
class ResolvingSpellCopyPayloadTest : FunSpec({

    val resolvingCopyProbe = card("Resolving Copy Probe") {
        manaCost = "{2}{W}"
        colorIdentity = "W"
        typeLine = "Sorcery"
        oracleText = "Return target permanent card with mana value 3 or less from your graveyard to the battlefield. If this spell was cast from a graveyard, you may copy this spell and may choose a new target for the copy. Flashback {4}{W}."

        spell {
            val permanentCard = target(
                "target permanent card with mana value 3 or less from your graveyard",
                TargetObject(
                    filter = TargetFilter(
                        GameObjectFilter.Permanent.manaValueAtMost(3).ownedByYou(),
                        zone = Zone.GRAVEYARD,
                    ),
                ),
            )
            effect = Effects.Move(permanentCard, Zone.BATTLEFIELD, fromZone = Zone.GRAVEYARD)
                .then(
                    ConditionalEffect(
                        condition = Conditions.WasCastFromZone(Zone.GRAVEYARD),
                        effect = MayEffect(
                            Effects.CopyTargetSpell(target = EffectTarget.Self),
                            descriptionOverride = "You may copy this resolving spell and choose a new target",
                        ),
                    ),
                )
        }

        keywordAbility(KeywordAbility.flashback("{4}{W}"))
    }

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + resolvingCopyProbe + BasiliskCollar + Bonesplitter)
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            skipMulligans = true,
            startingPlayer = 0,
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun resolveUntilPausedOrEmpty(driver: GameTestDriver) {
        var guard = 0
        while (guard++ < 30 && driver.state.stack.isNotEmpty() && !driver.isPaused) {
            driver.bothPass()
        }
    }

    test("a resolving copy preserves its target requirements after an explicit may decision") {
        val driver = newDriver()
        val you = driver.activePlayer!!
        val originalTarget = driver.putCardInGraveyard(you, "Basilisk Collar")
        val retargetCandidate = driver.putCardInGraveyard(you, "Bonesplitter")
        val opponentCandidate = driver.putCardInGraveyard(driver.player2, "Bonesplitter")
        val spell = driver.putCardInGraveyard(you, "Resolving Copy Probe")

        driver.giveMana(you, Color.WHITE, 2)
        driver.giveColorlessMana(you, 4)
        driver.submit(
            CastSpell(
                playerId = you,
                cardId = spell,
                targets = listOf(ChosenTarget.Card(originalTarget, you, Zone.GRAVEYARD)),
                useAlternativeCost = true,
                alternativeCostType = AlternativeCostType.FLASHBACK,
                paymentStrategy = PaymentStrategy.FromPool,
            ),
        ).error shouldBe null
        resolveUntilPausedOrEmpty(driver)

        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(you, true).error shouldBe null
        val retargetDecision = driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        retargetDecision.id.startsWith("copy-spell-target-") shouldBe true
        retargetDecision.legalTargets.values.flatten() shouldContain retargetCandidate

        val pausedState = driver.state
        pausedState.getEntity(spell)?.get<SpellOnStackComponent>() shouldBe null
        pausedState.getEntity(spell)?.get<TargetsComponent>() shouldBe null
        val copyContinuation = pausedState.continuationStack.last()
            .shouldBeInstanceOf<StormCopyTargetContinuation>()
        val payload = copyContinuation.resolvingSpellCopyPayload.shouldNotBeNull()
        payload.sourceSpellId shouldBe spell
        payload.effectiveSpellEffect.shouldNotBeNull()
        payload.targets?.targetRequirements shouldBe copyContinuation.spellTargetRequirements
        payload.targets?.targets?.single() shouldBe ChosenTarget.Card(originalTarget, you, Zone.GRAVEYARD)
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val restoredPausedState = json.decodeFromString(
            GameState.serializer(),
            json.encodeToString(GameState.serializer(), pausedState),
        )
        restoredPausedState shouldBe pausedState

        val wrongActor = driver.submitDecision(
            driver.player2,
            com.wingedsheep.engine.core.TargetsResponse(
                retargetDecision.id,
                mapOf(0 to listOf(retargetCandidate)),
            ),
        )
        wrongActor.error shouldBe "You are not the player who needs to make this decision"
        driver.state shouldBe pausedState
        retargetDecision.legalTargets.values.flatten() shouldNotContain opponentCandidate

        val response = com.wingedsheep.engine.core.TargetsResponse(
            retargetDecision.id,
            mapOf(0 to listOf(retargetCandidate)),
        )
        val processor = com.wingedsheep.engine.core.ActionProcessor(driver.cardRegistry)
        val originalFork = processor.process(
            pausedState,
            com.wingedsheep.engine.core.SubmitDecision(you, response),
        ).result
        val restoredFork = processor.process(
            restoredPausedState,
            com.wingedsheep.engine.core.SubmitDecision(you, response),
        ).result
        originalFork.error shouldBe null
        restoredFork.error shouldBe null
        originalFork.state shouldBe restoredFork.state
        originalFork.events shouldBe restoredFork.events

        val copyId = originalFork.state.stack.single { entityId ->
            originalFork.state.getEntity(entityId)?.has<CopyOfComponent>() == true
        }
        val copySpell = originalFork.state.getEntity(copyId)
            ?.get<SpellOnStackComponent>()
            .shouldNotBeNull()
        copySpell.castFromZone shouldBe Zone.GRAVEYARD
        copySpell.alternativeCost shouldBe AlternativeCostType.FLASHBACK
        copySpell.resolvingSpellEffectOverride.shouldNotBeNull()
        (originalFork.state.pendingDecision == null) shouldBe true
        val copyTargets = originalFork.state.getEntity(copyId)
            ?.get<TargetsComponent>()
            .shouldNotBeNull()
        copyTargets.targets.single().let { target ->
            (target as ChosenTarget.Card).cardId
        } shouldBe retargetCandidate
        copyTargets.targetRequirements shouldBe pausedState.continuationStack
            .last()
            .let { continuation ->
                continuation as com.wingedsheep.engine.core.StormCopyTargetContinuation
                continuation.spellTargetRequirements
            }

        driver.replaceState(originalFork.state)
        resolveUntilPausedOrEmpty(driver)
        driver.state.getZone(ZoneKey(you, Zone.BATTLEFIELD)) shouldContain retargetCandidate
        driver.state.getZone(ZoneKey(you, Zone.EXILE)) shouldContain spell
    }
})
