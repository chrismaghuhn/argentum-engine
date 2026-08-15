package com.wingedsheep.engine.security

import com.wingedsheep.engine.core.*
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * External actions must not be able to manufacture the private resume state
 * used after a paused cost or zone-change continuation. In particular, these
 * markers cannot be used to skip ReturnToHand/ReturnSelfToHand costs.
 */
class InternalResumeMarkerSecurityTest : FunSpec({
    val playerId = EntityId.generate()
    val sourceId = EntityId.generate()
    val abilityId = AbilityId("test-ability")
    val cardId = EntityId.generate()

    fun process(action: GameAction): ExecutionResult =
        ActionProcessor(CardRegistry())
            .process(GameState().copy(turnOrder = listOf(playerId)), action)
            .result

    fun assertRejected(result: ExecutionResult) {
        result.error shouldBe "Internal resume state cannot be set by a player"
    }

    test("a client cannot mark an ActivateAbility ReturnToHand cost as already paid") {
        assertRejected(
            process(
                ActivateAbility(
                    playerId = playerId,
                    sourceId = sourceId,
                    abilityId = abilityId,
                    preResolvedZoneChangeIds = listOf(EntityId.generate()),
                )
            )
        )
    }

    test("a client cannot mark a CastSpell ReturnToHand cost as already paid") {
        assertRejected(
            process(
                CastSpell(
                    playerId = playerId,
                    cardId = cardId,
                    preResolvedZoneChangeIds = listOf(EntityId.generate()),
                )
            )
        )
    }

    test("a client cannot provide the internal Sneak continuation marker") {
        assertRejected(
            process(
                CastSpell(
                    playerId = playerId,
                    cardId = cardId,
                    preResolvedSneakAttackDefenderId = EntityId.generate(),
                )
            )
        )
    }

    test("a client cannot provide the internal Web-Sling continuation marker") {
        assertRejected(
            process(
                CastSpell(
                    playerId = playerId,
                    cardId = cardId,
                    preResolvedWebSlingReturnedManaValue = 3,
                )
            )
        )
    }
})
