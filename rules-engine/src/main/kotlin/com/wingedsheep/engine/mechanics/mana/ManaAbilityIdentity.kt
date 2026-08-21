package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.scripting.ActivatedAbility
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Stable public identity for a mana ability choice.
 *
 * [com.wingedsheep.sdk.scripting.AbilityId] is an engine handle and can contain a JVM-local
 * generated counter. Payment plans therefore carry this structural identity instead. The current
 * source is still authoritative when a plan is submitted; it must expose the same structure for
 * the plan to bind successfully.
 */
object ManaAbilityIdentity {
    private val serialization = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun key(ability: ActivatedAbility): String {
        val encoded = serialization
            .encodeToJsonElement(ActivatedAbility.serializer(), ability)
            .jsonObject
        return JsonObject(encoded.filterKeys { it != "id" && it != "descriptionOverride" }).toString()
    }

    /** Stable identity for a basic-land subtype's synthesized mana ability. */
    fun intrinsic(color: Color?): String = "intrinsic:${color?.symbol ?: 'C'}"
}
