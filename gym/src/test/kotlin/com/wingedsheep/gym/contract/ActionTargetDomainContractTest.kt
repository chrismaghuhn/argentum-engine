package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject

class ActionTargetDomainContractTest : FunSpec({

    fun requirement(
        index: Int,
        minTargets: Int,
        maxTargets: Int,
        xConstrainsCount: Boolean = false,
    ) = TargetInfo(
        index = index,
        description = "requirement $index",
        minTargets = minTargets,
        maxTargets = maxTargets,
        validTargets = listOf(EntityId("candidate-$index")),
        xConstrainsCount = xConstrainsCount,
    )

    test("targetless fixed domain accepts only an empty payload") {
        val certification = TargetPayloadPartition.certify(emptyList())
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        certification.partition(0) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(emptyList())
        certification.partition(1) shouldBe TargetPayloadPartition.PayloadPartition.Rejected(
            TargetPayloadPartition.UnsupportedReason.PAYLOAD_LENGTH_OUT_OF_RANGE,
        )
    }

    test("one optional requirement accepts zero or one target") {
        val certification = TargetPayloadPartition.certify(listOf(requirement(0, 0, 1)))
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        certification.partition(0) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(0))
        certification.partition(1) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(1))
    }

    test("one requirement may have any finite resolved cardinality") {
        val certification = TargetPayloadPartition.certify(listOf(requirement(0, 1, 3)))
            .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()

        certification.partition(1) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(1))
        certification.partition(2) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(2))
        certification.partition(3) shouldBe TargetPayloadPartition.PayloadPartition.Accepted(listOf(3))
    }

    test("fixed Bite Down and Brass Squire shaped payloads preserve slot order") {
        val shapes = listOf(
            "Bite Down" to listOf(requirement(0, 1, 1), requirement(1, 1, 1)),
            "Brass Squire" to listOf(requirement(0, 1, 1), requirement(1, 1, 1)),
        )
        val slot0 = ChosenTarget.Permanent(EntityId("slot-0"))
        val slot1 = ChosenTarget.Permanent(EntityId("slot-1"))

        shapes.forEach { (_, requirements) ->
            val certification = TargetPayloadPartition.certify(requirements)
                .shouldBeInstanceOf<TargetPayloadPartition.Certification.Supported>()
            val partition = certification.partition(2)
                .shouldBeInstanceOf<TargetPayloadPartition.PayloadPartition.Accepted>()

            partition.counts shouldBe listOf(1, 1)
            val payload = listOf(slot0, slot1)
            payload.take(partition.counts[0]) shouldBe listOf(slot0)
            payload.drop(partition.counts[0]) shouldBe listOf(slot1)
        }
    }

    test("two variable requirements are rejected when total lengths collide") {
        TargetPayloadPartition.certify(
            listOf(requirement(0, 0, 1), requirement(1, 1, 2)),
        ) shouldBe TargetPayloadPartition.Certification.Unsupported(
            TargetPayloadPartition.UnsupportedReason.AMBIGUOUS_FLAT_PARTITION,
        )
    }

    test("unresolved X cardinality is rejected without using the placeholder max") {
        TargetPayloadPartition.certify(listOf(requirement(0, 0, 1, xConstrainsCount = true))) shouldBe
            TargetPayloadPartition.Certification.Unsupported(
                TargetPayloadPartition.UnsupportedReason.UNRESOLVED_X,
            )
    }

    test("invalid cardinality is rejected") {
        TargetPayloadPartition.certify(listOf(requirement(0, 2, 1))) shouldBe
            TargetPayloadPartition.Certification.Unsupported(
                TargetPayloadPartition.UnsupportedReason.INVALID_CARDINALITY,
            )
    }

    test("a target-bearing action without canonical requirements cannot use legacy flat fields") {
        val action = LegalAction(
            action = CastSpell(EntityId("player"), EntityId("spell")),
            actionType = "CastSpell",
            description = "targeted spell",
            requiresTargets = true,
            validTargets = listOf(EntityId("legacy-candidate")),
            minTargets = 1,
            targetCount = 1,
        )

        ActionPayloadRequirements.requiredPayloadFields(action) shouldBe setOf("targets")
        shouldThrow<IllegalArgumentException> {
            ActionPayloadRequirements.requireTargetDomainSupported(action)
        }
    }

    test("targetless actions do not require a target payload") {
        val action = LegalAction(
            action = CastSpell(EntityId("player"), EntityId("spell")),
            actionType = "CastSpell",
            description = "targetless spell",
            requiresTargets = false,
            targetRequirements = emptyList(),
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
        )

        ActionPayloadRequirements.requiredPayloadFields(action).shouldBeEmpty()
        ActionPayloadRequirements.missingRequiredFields(action, buildJsonObject {}) shouldBe emptyList()
        ActionPayloadRequirements.requireTargetDomainSupported(action)
    }
})
