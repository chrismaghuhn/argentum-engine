package com.wingedsheep.sdk.serialization

import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
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
 * emits an object such as `{"type":"CommanderColorIdentityCount"}`. Any other format (the
 * corpus tree walker, a binary format) sees the plain structure with its one `amount` element.
 */
object CostAtomPayLifeSerializer : KSerializer<CostAtom.PayLife> {
    override val descriptor: SerialDescriptor =
        // Keep the existing polymorphic discriminator used by every committed card snapshot.
        buildClassSerialDescriptor("AtomPayLife") {
            element("amount", DynamicAmount.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: CostAtom.PayLife) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder == null) {
            encoder.encodeStructure(descriptor) {
                encodeSerializableElement(descriptor, 0, DynamicAmount.serializer(), value.amount)
            }
            return
        }
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
            ?: return decoder.decodeStructure(descriptor) {
                var amount: DynamicAmount? = null
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> amount = decodeSerializableElement(descriptor, 0, DynamicAmount.serializer())
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected element $index for CostAtom.PayLife")
                    }
                }
                CostAtom.PayLife(amount ?: error("Missing amount for CostAtom.PayLife"))
            }
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
