package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RED characterization for the next A9 chosen-input boundary: repeatable activated abilities
 * publish a semantic `repeatCount`, but the durable stored-action validator has no corresponding
 * public-domain contract yet.
 */
class ChosenSemanticRepeatCountGapTest : FunSpec({
    test("public repeatCount action is rejected by the current stored-domain validator") {
        val player = EntityId("player")
        val source = EntityId("repeatable-source")
        val candidate = ObservationCanonicalizer.semanticActionFingerprint(
            LegalActionView(
                actionId = 0,
                kind = "ActivateAbility",
                description = "repeatable activated ability",
                affordable = true,
                sourceEntityId = source,
                requiresStructuredAction = true,
                requiredPayloadFields = listOf("repeatCount"),
                actionSemantics = buildJsonObject {
                    put("type", "ActivateAbility")
                    put("abilityKey", buildJsonObject {
                        put("ability", buildJsonObject {})
                    })
                    put("repeatCount", 2)
                },
            ),
        )
        val domain = CompleteLegalDomainV1(
            kind = CompleteLegalDomainKind.ACTION_CANDIDATES,
            candidates = listOf(candidate),
        )
        val action = ActivateAbility(
            playerId = player,
            sourceId = source,
            abilityId = AbilityId("repeatable-ability"),
            repeatCount = 2,
        )

        val failure = shouldThrow<IllegalArgumentException> {
            ChosenSemanticActionV1.fromRecordedAction(domain, candidate, action)
        }

        failure.message shouldBe
            "Chosen action payload has no complete stored-domain validator for: repeatCount"
    }
})
