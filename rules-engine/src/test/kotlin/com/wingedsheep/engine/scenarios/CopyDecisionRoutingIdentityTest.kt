package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActionProcessor
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.stack.CopyTargetSpellExecutor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.PlayerComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.effects.CopyTargetSpellEffect
import com.wingedsheep.sdk.scripting.effects.DealDamageEffect
import com.wingedsheep.sdk.scripting.targets.AnyTarget
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Routing IDs are transient handles, not semantic copy identities. A copy-target question takes its
 * decision id from the routing counter carried by [GameState.nextRoutingId]: two identical states
 * taking the same copy action ask under the same id (reproducible replay and forks), while
 * successive copy-target occurrences along one timeline receive distinct ids, so a response to an
 * earlier occurrence is rejected by a later one.
 */
class CopyDecisionRoutingIdentityTest : FunSpec({

    val services = EngineServices(CardRegistry())

    fun buildState(p1: EntityId, p2: EntityId, spellId: EntityId): GameState {
        val requirement = AnyTarget()
        val card = CardComponent(
            cardDefinitionId = "Routing Probe",
            name = "Routing Probe",
            manaCost = ManaCost.parse("{1}{R}"),
            typeLine = TypeLine.instant(),
            oracleText = "",
            ownerId = p1,
            spellEffect = DealDamageEffect(1, EffectTarget.ContextTarget(0)),
        )
        return GameState(
            activePlayerId = p1,
            priorityPlayerId = p1,
            turnOrder = listOf(p1, p2),
        )
            .withEntity(p1, ComponentContainer.of(PlayerComponent("P1"), LifeTotalComponent(20)))
            .withEntity(p2, ComponentContainer.of(PlayerComponent("P2"), LifeTotalComponent(20)))
            .withEntity(
                spellId,
                ComponentContainer.of(
                    card,
                    OwnerComponent(p1),
                    ControllerComponent(p1),
                    SpellOnStackComponent(casterId = p1),
                    TargetsComponent(
                        targets = listOf(ChosenTarget.Player(p2)),
                        targetRequirements = listOf(requirement),
                    ),
                ),
            )
            .copy(stack = listOf(spellId))
    }

    fun executeCopy(state: GameState, spellId: EntityId, controllerId: EntityId) =
        CopyTargetSpellExecutor(
            dynamicAmountEvaluator = services.dynamicAmountEvaluator,
            targetFinder = services.targetFinder,
            targetValidator = services.targetValidator,
        ).execute(
            state = state,
            effect = CopyTargetSpellEffect(target = EffectTarget.SpecificEntity(spellId)),
            context = EffectContext(sourceId = spellId, controllerId = controllerId),
        )

    /** Answer [decision] as SubmitDecision does: consume its suspension and resume the copy. */
    fun answer(state: GameState, decision: ChooseTargetsDecision, target: EntityId): GameState {
        val resumed = services.continuationHandler.resume(
            state,
            TargetsResponse(decision.id, mapOf(0 to listOf(target))),
        )
        resumed.error shouldBe null
        resumed.pendingDecision shouldBe null
        return resumed.state
    }

    test("identical states taking the same copy action allocate the same routing ID") {
        val p1 = EntityId.generate()
        val p2 = EntityId.generate()
        val spellId = EntityId.generate()
        val state = buildState(p1, p2, spellId)

        val first = executeCopy(state, spellId, p1)
        val replay = executeCopy(state, spellId, p1)
        val firstDecision = first.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val replayDecision = replay.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()

        replayDecision.id shouldBe firstDecision.id
        replay.state shouldBe first.state
    }

    test("successive copy-target occurrences along one timeline receive distinct routing IDs") {
        val p1 = EntityId.generate()
        val p2 = EntityId.generate()
        val spellId = EntityId.generate()
        val state = buildState(p1, p2, spellId)

        val firstResult = executeCopy(state, spellId, p1)
        val first = firstResult.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val afterFirst = answer(firstResult.state, first, first.legalTargets.values.flatten().first())
        val second = executeCopy(afterFirst, spellId, p1)
            .pendingDecision
            .shouldBeInstanceOf<ChooseTargetsDecision>()

        first.id shouldNotBe second.id
    }

    test("a response from an earlier copy-target occurrence is rejected by a later occurrence") {
        val p1 = EntityId.generate()
        val p2 = EntityId.generate()
        val spellId = EntityId.generate()
        val state = buildState(p1, p2, spellId)
        val firstResult = executeCopy(state, spellId, p1)
        val first = firstResult.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val staleTarget = first.legalTargets.values.flatten().first()
        val afterFirst = answer(firstResult.state, first, staleTarget)
        val secondResult = executeCopy(afterFirst, spellId, p1)
        val secondState = secondResult.state
        val second = secondResult.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()

        val stale = ActionProcessor(services).process(
            secondState,
            SubmitDecision(
                playerId = p1,
                response = TargetsResponse(first.id, mapOf(0 to listOf(staleTarget))),
            ),
        ).result

        stale.error shouldBe "Decision ID mismatch: expected ${second.id}, got ${first.id}"
        stale.state shouldBe secondState
    }
})
