package com.wingedsheep.sdk.serialization

import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Keeps the historical compact JSON shape for fixed life payments while allowing
 * activation-time dynamic amounts to use the normal DynamicAmount representation.
 *
 * Existing card snapshots contain `"amount": 3`; only a genuinely dynamic payment
 * emits an object such as `{"type":"CommanderColorIdentityCount"}`.
 */
object CostAtomPayLifeSerializer : KSerializer<CostAtom.PayLife> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("CostAtom.PayLife") {
            element("amount", DynamicAmount.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: CostAtom.PayLife) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("CostAtom.PayLife requires a JSON encoder")
        val amount = when (val dynamicAmount = value.amount) {
            is DynamicAmount.Fixed -> JsonPrimitive(dynamicAmount.amount)
            else -> CardSerialization.json.encodeToJsonElement(
                DynamicAmount.serializer(),
                dynamicAmount
            )
        }
        jsonEncoder.encodeJsonElement(buildJsonObject { put("amount", amount) })
    }

    override fun deserialize(decoder: Decoder): CostAtom.PayLife {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("CostAtom.PayLife requires a JSON decoder")
        val objectValue = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: error("Expected a JSON object for CostAtom.PayLife")
        val amount = objectValue["amount"]
            ?: error("Missing amount for CostAtom.PayLife")
        val dynamicAmount = if (amount is JsonPrimitive && !amount.isString) {
            DynamicAmount.Fixed(amount.int)
        } else {
            CardSerialization.json.decodeFromJsonElement(DynamicAmount.serializer(), amount)
        }
        return CostAtom.PayLife(dynamicAmount)
    }
}
