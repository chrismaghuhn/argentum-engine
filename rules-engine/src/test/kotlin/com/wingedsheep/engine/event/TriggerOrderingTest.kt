package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.references.Player
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Synthetic characterization and regression coverage for CR 603.3b trigger ordering.
 *
 * The fixtures enter through [TriggerProcessor] rather than a card-specific detector. This keeps
 * the contract visible: detector order is APNAP transport order, same-controller relative order is
 * an external choice, and all later target/may/continuation paths consume that chosen order.
 */
class TriggerOrderingTest : FunSpec({

    test("TO-01: same-controller simultaneous triggers require external ordering") {
        val driver = newDriver()
        val result = process(driver, listOf(
            syntheticTrigger(driver, "first"),
            syntheticTrigger(driver, "second")
        ))

        result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
    }

    test("TO-02: ordered trigger handles map to the chosen stack order") {
        val driver = newDriver()
        val result = process(driver, listOf(
            syntheticTrigger(driver, "first"),
            syntheticTrigger(driver, "second")
        ))
        val decision = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        decision.objects shouldBe listOf(EntityId("trigger-order-object-0"), EntityId("trigger-order-object-1"))
        decision.objectLabels?.values?.toList()?.size shouldBe 2
        decision.objectLabels?.values?.none { it.contains("PendingTrigger") } shouldBe true

        driver.replaceState(result.state)
        val resumed = driver.submitDecision(
            decision.playerId,
            OrderedResponse(decision.id, decision.objects.reversed())
        )

        resumed.isSuccess shouldBe true
        stackedTriggers(resumed.state).map { it.description } shouldBe listOf("second", "first")
    }

    test("TO-03: APNAP asks each controller in turn and never cross-orders controllers") {
        val driver = newDriver()
        val active = driver.state.activePlayerId!!
        val nonActive = driver.state.turnOrder.first { it != active }
        val result = process(driver, listOf(
            syntheticTrigger(driver, "active-first", controllerId = active),
            syntheticTrigger(driver, "active-second", controllerId = active),
            syntheticTrigger(driver, "nap-first", controllerId = nonActive),
            syntheticTrigger(driver, "nap-second", controllerId = nonActive)
        ))
        val activeOrder = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        activeOrder.playerId shouldBe active
        activeOrder.objects.size shouldBe 2

        driver.replaceState(result.state)
        val afterActive = driver.submitDecision(
            active,
            OrderedResponse(activeOrder.id, activeOrder.objects.reversed())
        )
        val napOrder = afterActive.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        napOrder.playerId shouldBe nonActive
        napOrder.objects.size shouldBe 2

        val final = driver.submitDecision(
            nonActive,
            OrderedResponse(napOrder.id, napOrder.objects.reversed())
        )
        final.isSuccess shouldBe true
        stackedTriggers(final.state).map { it.description } shouldBe listOf(
            "active-second", "active-first", "nap-second", "nap-first"
        )
    }

    test("TO-13: APNAP normalization reunites a controller split by detector batches") {
        val driver = newDriver()
        val active = driver.state.activePlayerId!!
        val nonActive = driver.state.turnOrder.first { it != active }
        val result = process(driver, listOf(
            syntheticTrigger(driver, "active-first", controllerId = active),
            syntheticTrigger(driver, "nap-only", controllerId = nonActive),
            syntheticTrigger(driver, "active-second", controllerId = active)
        ))

        val activeOrder = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        activeOrder.playerId shouldBe active
        activeOrder.objects.size shouldBe 2
    }

    test("TO-14: detector-marked reflexive triggers enter the second CR 603.3b stage") {
        val driver = newDriver()
        val reflexiveSource = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val reflexive = TriggerDetector(driver.cardRegistry).detectTriggers(
            driver.state,
            listOf(
                ReflexiveAbilityTriggeredEvent(
                    sourceId = reflexiveSource,
                    sourceName = "reflexive",
                    controllerId = driver.player1,
                    reflexiveEffect = Effects.DrawCards(1)
                )
            )
        ).single()
        reflexive.stage shouldBe TriggerStage.REFLEXIVE

        val result = process(driver, listOf(
            reflexive,
            syntheticTrigger(driver, "normal-first"),
            syntheticTrigger(driver, "normal-second")
        ))

        val normalOrder = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        normalOrder.objects.size shouldBe 2
        normalOrder.objectLabels!!.values.all { it.startsWith("normal-") } shouldBe true
    }

    test("TO-15: duplicate trigger labels are disambiguated without exposing trigger context") {
        val driver = newDriver()
        val result = process(driver, listOf(
            syntheticTrigger(
                driver,
                "same",
                triggerContext = TriggerContext(
                    triggeringPlayerId = driver.player1,
                    damageAmount = 1
                )
            ),
            syntheticTrigger(
                driver,
                "same",
                triggerContext = TriggerContext(
                    triggeringPlayerId = driver.player1,
                    damageAmount = 2
                )
            )
        ))

        val decision = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        val labels = decision.objects.map { decision.objectLabels!!.getValue(it) }

        labels.toSet().size shouldBe 2
        labels.all { it.startsWith("same: same") } shouldBe true
        labels.none { it.contains("damage", ignoreCase = true) } shouldBe true
        labels.none { it.contains("trigger-order-object") } shouldBe true
    }

    test("TO-04: invalid ordering responses fail closed without consuming the continuation") {
        val driver = newDriver()
        val result = process(driver, listOf(
            syntheticTrigger(driver, "first"),
            syntheticTrigger(driver, "second")
        ))
        val decision = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        val before = result.state
        driver.replaceState(before)

        val invalid = driver.submitDecision(
            decision.playerId,
            OrderedResponse(decision.id, listOf(decision.objects.first(), decision.objects.first()))
        )

        invalid.isSuccess shouldBe false
        driver.state shouldBe before
        driver.state.pendingDecision shouldBe decision
        driver.state.peekContinuation().shouldBeInstanceOf<TriggerOrderingContinuation>()
    }

    test("TO-05: same-controller targetless may triggers order before may choices") {
        val driver = newDriver()
        val may = MayEffect(Effects.DrawCards(1))
        val result = process(driver, listOf(
            syntheticTrigger(driver, "may-first", effect = may),
            syntheticTrigger(driver, "may-second", effect = may)
        ))
        val order = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()

        driver.replaceState(result.state)
        val ordered = driver.submitDecision(
            driver.player1,
            OrderedResponse(order.id, order.objects.reversed())
        )
        ordered.isSuccess shouldBe true
        stackedTriggers(ordered.state).map { it.description } shouldBe listOf("may-second", "may-first")

        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        val secondMay = driver.submitYesNo(driver.player1, choice = false)
        secondMay.isSuccess shouldBe true
        driver.bothPass()
        driver.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        driver.submitYesNo(driver.player1, choice = false).isSuccess shouldBe true
    }

    test("TO-06: chosen ordering survives later target selections without reordering") {
        val driver = newDriver()
        val result = process(driver, listOf(
            syntheticTrigger(driver, "target-first", targetRequirement = Targets.Player),
            syntheticTrigger(driver, "target-second", targetRequirement = Targets.Player)
        ))
        val order = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()

        driver.replaceState(result.state)
        val firstTarget = driver.submitDecision(
            driver.player1,
            OrderedResponse(order.id, order.objects.reversed())
        )
        firstTarget.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val secondTarget = driver.submitTargetSelection(driver.player1, listOf(driver.player2))
        secondTarget.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val final = driver.submitTargetSelection(driver.player1, listOf(driver.player2))

        final.isSuccess shouldBe true
        stackedTriggers(final.state).map { it.description } shouldBe listOf("target-second", "target-first")
    }

    test("TO-07: batched may triggers order before one shared may decision and retain that order") {
        val driver = newDriver()
        val may = MayEffect(Effects.DrawCards(1))
        val result = process(driver, listOf(
            syntheticTrigger(driver, "batch-first", effect = may, targetRequirement = Targets.Player, abilityId = "batch"),
            syntheticTrigger(driver, "batch-second", effect = may, targetRequirement = Targets.Player, abilityId = "batch")
        ))
        val order = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()

        driver.replaceState(result.state)
        val batchResult = driver.submitDecision(
            driver.player1,
            OrderedResponse(order.id, order.objects.reversed())
        )
        val batch = batchResult.pendingDecision.shouldBeInstanceOf<BatchYesNoDecision>()
        batch.count shouldBe 2

        driver.submitBatchYesNo(driver.player1, choice = true, applyToAll = true)
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(driver.player1, listOf(driver.player2))
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val final = driver.submitTargetSelection(driver.player1, listOf(driver.player2))
        final.isSuccess shouldBe true
        stackedTriggers(final.state).map { it.description } shouldBe listOf("batch-second", "batch-first")
    }

    test("TO-08: delayed occurrence choice remains separate before later trigger ordering") {
        val driver = newDriver()
        val candidateA = syntheticTrigger(
            driver,
            "delayed-a",
            triggerContext = TriggerContext(triggeringPlayerId = driver.player1),
            consumesDelayedTriggerId = "delayed-once"
        )
        val candidateB = syntheticTrigger(
            driver,
            "delayed-b",
            triggerContext = TriggerContext(triggeringPlayerId = driver.player2),
            consumesDelayedTriggerId = "delayed-once"
        )
        val marker = candidateA.copy(
            occurrenceChoice = listOf(candidateA.toOccurrenceCandidate(), candidateB.toOccurrenceCandidate())
        )
        val result = process(driver, listOf(
            marker,
            syntheticTrigger(driver, "later-first"),
            syntheticTrigger(driver, "later-second")
        ))
        val occurrence = result.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()

        driver.replaceState(result.state)
        val afterOccurrence = driver.submitDecision(
            driver.player1,
            OptionChosenResponse(occurrence.id, optionIndex = 0)
        )
        val laterOrder = afterOccurrence.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        laterOrder.objects.size shouldBe 3
    }

    test("TO-09: ordering keeps delayed, saga, granter, and reflexive payloads intact") {
        val driver = newDriver()
        val granter = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val pipeline = PipelineState(storedNumbers = mapOf("reflexive" to 7))
        val result = process(driver, listOf(
            syntheticTrigger(driver, "delayed", consumesDelayedTriggerId = "delayed-id"),
            syntheticTrigger(driver, "saga", sagaChapterInfo = SagaChapterInfo(2, 3)),
            syntheticTrigger(driver, "granted", granterId = granter),
            syntheticTrigger(
                driver,
                "reflexive",
                carriedPipeline = pipeline,
                stage = TriggerStage.REFLEXIVE
            )
        ))
        val order = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()

        driver.replaceState(result.state)
        val final = driver.submitDecision(
            driver.player1,
            OrderedResponse(order.id, order.objects)
        )
        final.isSuccess shouldBe true
        val components = stackedTriggers(final.state).associateBy { it.description }
        components.getValue("saga").sagaChapterInfo shouldBe SagaChapterInfo(2, 3)
        components.getValue("granted").granterId shouldBe granter
        components.getValue("reflexive").carriedPipeline shouldBe pipeline
    }

    test("TO-10: paused trigger ordering serializes as a full GameState") {
        val driver = newDriver()
        val result = process(driver, listOf(
            syntheticTrigger(driver, "first", stage = TriggerStage.REFLEXIVE),
            syntheticTrigger(driver, "second", stage = TriggerStage.REFLEXIVE)
        ))
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val encoded = json.encodeToString(GameState.serializer(), result.state)
        val restored = json.decodeFromString(GameState.serializer(), encoded)

        restored shouldBe result.state
        encoded.contains("PendingTrigger.toString") shouldBe false
        restored.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()
        restored.peekContinuation().shouldBeInstanceOf<TriggerOrderingContinuation>()
    }

    test("TO-11: response is exactly once and forks can choose divergent orders") {
        val source = newDriver()
        val result = process(source, listOf(
            syntheticTrigger(source, "first"),
            syntheticTrigger(source, "second")
        ))
        val order = result.pendingDecision.shouldBeInstanceOf<OrderObjectsDecision>()

        val firstFork = newDriver().also { it.replaceState(result.state) }
        val secondFork = newDriver().also { it.replaceState(result.state) }
        val first = firstFork.submitDecision(
            order.playerId,
            OrderedResponse(order.id, order.objects)
        )
        val second = secondFork.submitDecision(
            order.playerId,
            OrderedResponse(order.id, order.objects.reversed())
        )
        stackedTriggers(first.state).map { it.description } shouldBe listOf("first", "second")
        stackedTriggers(second.state).map { it.description } shouldBe listOf("second", "first")

        val after = firstFork.state
        val stale = firstFork.submitDecision(
            order.playerId,
            OrderedResponse(order.id, order.objects)
        )
        stale.isSuccess shouldBe false
        firstFork.state shouldBe after
    }
})

private fun newDriver(): GameTestDriver = GameTestDriver().also {
    it.registerCards(TestCards.all)
    it.initMirrorMatch(Deck.of("Forest" to 40))
}

private fun process(driver: GameTestDriver, triggers: List<PendingTrigger>): ExecutionResult =
    EngineServices(driver.cardRegistry).triggerProcessor.processTriggers(driver.state, triggers)

private fun syntheticTrigger(
    driver: GameTestDriver,
    label: String,
    controllerId: EntityId = driver.player1,
    effect: Effect = Effects.DrawCards(1),
    targetRequirement: TargetRequirement? = null,
    abilityId: String = "synthetic-$label",
    triggerContext: TriggerContext = TriggerContext(),
    consumesDelayedTriggerId: String? = null,
    sagaChapterInfo: SagaChapterInfo? = null,
    granterId: EntityId? = null,
    carriedPipeline: PipelineState? = null,
    stage: TriggerStage = TriggerStage.NORMAL
): PendingTrigger = PendingTrigger(
    ability = TriggeredAbility.create(
        trigger = EventPattern.StepEvent(Step.UPKEEP, Player.You),
        effect = effect,
        targetRequirement = targetRequirement,
        descriptionOverride = label
    ).copy(id = AbilityId(abilityId)),
    sourceId = driver.putPermanentOnBattlefield(controllerId, "Sol Ring"),
    sourceName = label,
    controllerId = controllerId,
    triggerContext = triggerContext,
    consumesDelayedTriggerId = consumesDelayedTriggerId,
    sagaChapterInfo = sagaChapterInfo,
    granterId = granterId,
    carriedPipeline = carriedPipeline,
    stage = stage
)

private fun stackedTriggers(state: GameState): List<TriggeredAbilityOnStackComponent> =
    state.stack.mapNotNull { state.getEntity(it)?.get<TriggeredAbilityOnStackComponent>() }
