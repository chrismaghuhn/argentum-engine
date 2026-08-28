package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Contract coverage for the new ordered, list-indexed V3 payment carrier. */
class PaymentPlanV3ContractTest : FunSpec({
    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    test("V3 round-trips ordered activations and one global allocation vocabulary") {
        val poolKey = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN)
        val plan = PaymentPlanV3(
            activations = listOf(
                SourceActivationV2(
                    sourceId = EntityId("forest"),
                    manaAbilityKey = "intrinsic:G",
                    productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                ),
                SourceActivationV2(
                    sourceId = EntityId("signet"),
                    manaAbilityKey = "signet-key",
                    productionChoice = ProductionChoice(
                        producedColor = PaymentManaColor.BLACK,
                        fixedOutputs = listOf(
                            FixedManaOutput(0, PaymentManaColor.BLACK),
                            FixedManaOutput(1, PaymentManaColor.GREEN),
                        ),
                    ),
                    activationCostOrder = listOf(
                        ActivationCostComponentRefV1.ManaComponent,
                        ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                    ),
                    activationCostAllocation = listOf(
                        PaymentAllocationV1(
                            target = PaymentTargetV1.ActivationCostUnit(1, 0, 0),
                            resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                        ),
                    ),
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.InitialPoolResource(poolKey),
                ),
            ),
        )

        val encoded = json.encodeToString(PaymentPlanV3.serializer(), plan)
        val decoded = json.decodeFromString(PaymentPlanV3.serializer(), encoded)

        decoded shouldBe plan
        encoded.contains("activationId") shouldBe false
        decoded.activations.map { it.sourceId } shouldBe listOf(EntityId("forest"), EntityId("signet"))
    }

    test("atomic cost units address both units of a generic symbol independently") {
        val units = listOf(
            AtomicManaCostUnitV1(0, 0, PaymentCostKindV1.GENERIC),
            AtomicManaCostUnitV1(0, 1, PaymentCostKindV1.GENERIC),
        )

        units.map { it.unitIndexWithinSymbol } shouldBe listOf(0, 1)
    }
})
