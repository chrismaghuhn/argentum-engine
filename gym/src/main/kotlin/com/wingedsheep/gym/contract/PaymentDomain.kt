package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.ManaSource
import com.wingedsheep.engine.mechanics.mana.supportsPaymentPlanV1
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.ManaSymbol
import kotlinx.serialization.Serializable

const val PAYMENT_DOMAIN_VERSION: Int = 1

@Serializable
enum class PaymentCostKind {
    COLORED,
    COLORLESS,
    GENERIC,
}

@Serializable
data class PaymentCostUnitDomain(
    val symbolIndex: Int,
    val kind: PaymentCostKind,
    val amount: Int,
    val allowedColors: Set<PaymentManaColor> = emptySet(),
)

@Serializable
data class PaymentPoolDomain(
    val white: Int = 0,
    val blue: Int = 0,
    val black: Int = 0,
    val red: Int = 0,
    val green: Int = 0,
    val colorless: Int = 0,
)

/** One exact mana-ability identity and its explicit production choices. */
@Serializable
data class PaymentSourceActivationDomain(
    val sourceId: com.wingedsheep.sdk.model.EntityId,
    val sourceName: String,
    val manaAbilityKey: String,
    val productionChoices: List<com.wingedsheep.engine.core.ProductionChoice>,
)

/**
 * Complete action-level payment domain for the supported ordinary fixed-cost slice.
 *
 * This is deliberately separate from the pending [ManaSourcesDomain]. It contains no automatic
 * payment suggestion: a controller must submit a [com.wingedsheep.engine.core.PaymentPlanV1]
 * whose source, production, pool, and allocation choices are all explicit.
 */
@Serializable
data class PaymentDomainV1(
    val version: Int = PAYMENT_DOMAIN_VERSION,
    val requiredCost: String,
    val costUnits: List<PaymentCostUnitDomain>,
    val currentPool: PaymentPoolDomain,
    val sourceActivations: List<PaymentSourceActivationDomain>,
)

/**
 * Builds the public action-level domain from the existing engine mana-source discovery. This class
 * never solves or suggests a payment; it only publishes exact source/ability/color candidates.
 */
class PaymentDomainBuilder(
    private val manaSolver: ManaSolver,
) {
    fun build(
        state: GameState,
        playerId: com.wingedsheep.sdk.model.EntityId,
        requiredCost: String,
    ): PaymentDomainV1? {
        val cost = runCatching { ManaCost.parse(requiredCost) }.getOrNull() ?: return null
        val costUnits = cost.symbols.mapIndexed { index, symbol -> symbol.toDomain(index) ?: return null }

        val pool = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
        // Restricted mana has no stable public bucket identity in V1. Do not publish a partial
        // domain that would force the engine to choose a restricted bucket during submission.
        if (pool.restrictedMana.isNotEmpty()) return null

        val sourceActivations = buildList {
            for (source in manaSolver.findAvailableManaSources(state, playerId).sortedBy { it.entityId.value }) {
                if (!source.supportsPaymentPlanV1()) return null
                addAll(source.toDomain() ?: return null)
            }
        }

        return PaymentDomainV1(
            requiredCost = requiredCost,
            costUnits = costUnits,
            currentPool = PaymentPoolDomain(
                white = pool.white,
                blue = pool.blue,
                black = pool.black,
                red = pool.red,
                green = pool.green,
                colorless = pool.colorless,
            ),
            sourceActivations = sourceActivations,
        )
    }

    private fun ManaSymbol.toDomain(index: Int): PaymentCostUnitDomain? = when (this) {
        is ManaSymbol.Colored -> PaymentCostUnitDomain(
            symbolIndex = index,
            kind = PaymentCostKind.COLORED,
            amount = 1,
            allowedColors = setOf(PaymentManaColor.fromEngine(color)),
        )
        is ManaSymbol.Colorless -> PaymentCostUnitDomain(
            symbolIndex = index,
            kind = PaymentCostKind.COLORLESS,
            amount = 1,
            allowedColors = setOf(PaymentManaColor.COLORLESS),
        )
        is ManaSymbol.Generic -> PaymentCostUnitDomain(
            symbolIndex = index,
            kind = PaymentCostKind.GENERIC,
            amount = amount,
        )
        // The first slice intentionally does not hide hybrid, Phyrexian, twobrid, or X choices.
        else -> null
    }

    private fun ManaSource.toDomain(): List<PaymentSourceActivationDomain>? {
        if (
            !supportsPaymentPlanV1() ||
            requiresSacrifice ||
            tapPermanentsSubCost != null ||
            manaAmount != 1 ||
            bonusManaPerTap != 0 ||
            bonusManaColorlessPerTap != 0 ||
            bonusManaColor != null ||
            bonusManaIsAnyColor ||
            restriction != null ||
            colorRestrictions.isNotEmpty() ||
            colorRiders.isNotEmpty() ||
            hasContextSensitiveAbilities ||
            colorActivationManaCost.isNotEmpty() ||
            colorPainCost.isNotEmpty() ||
            colorlessPainCost != 0 ||
            colorsRequiringSacrifice.isNotEmpty()
        ) return null

        val choicesByAbility = linkedMapOf<String, MutableSet<PaymentManaColor>>()
        for (color in Color.entries) {
            if (color !in producesColors) continue
            val abilities = manaAbilityOptionsFor(color)
            if (abilities.isEmpty()) {
                choicesByAbility.getOrPut(ManaAbilityIdentity.intrinsic(color)) { linkedSetOf() }
                    .add(PaymentManaColor.fromEngine(color))
            } else {
                abilities.forEach { ability ->
                    choicesByAbility.getOrPut(ManaAbilityIdentity.key(ability)) { linkedSetOf() }
                        .add(PaymentManaColor.fromEngine(color))
                }
            }
        }
        if (producesColorless) {
            val abilities = manaAbilityOptionsFor(null)
            if (abilities.isEmpty()) {
                choicesByAbility.getOrPut(ManaAbilityIdentity.intrinsic(null)) { linkedSetOf() }
                    .add(PaymentManaColor.COLORLESS)
            } else {
                abilities.forEach { ability ->
                    choicesByAbility.getOrPut(ManaAbilityIdentity.key(ability)) { linkedSetOf() }
                        .add(PaymentManaColor.COLORLESS)
                }
            }
        }
        if (choicesByAbility.isEmpty()) return null

        return choicesByAbility.entries.sortedBy { it.key }.map { (manaAbilityKey, colors) ->
            PaymentSourceActivationDomain(
                sourceId = entityId,
                sourceName = name,
                manaAbilityKey = manaAbilityKey,
                productionChoices = colors.sortedBy(PaymentManaColor::ordinal).map {
                    com.wingedsheep.engine.core.ProductionChoice(producedColor = it)
                },
            )
        }
    }
}
