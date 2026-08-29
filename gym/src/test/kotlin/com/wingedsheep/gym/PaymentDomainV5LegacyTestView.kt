package com.wingedsheep.gym

import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.gym.contract.CertifiedFloatingManaBucketDomainV4
import com.wingedsheep.gym.contract.PaymentCostKind
import com.wingedsheep.gym.contract.PaymentCostUnitDomain
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.PaymentPoolDomainV4
import com.wingedsheep.gym.contract.PaymentSourceActivationDomain
import com.wingedsheep.sdk.core.Subtype

/**
 * Test-only projections used while historical V1/V2 assertions are migrated to the V5 wire
 * shape. They are never serialized and are intentionally absent from the production DTO.
 */
internal val PaymentDomainV5.costUnits: List<PaymentCostUnitDomain>
    get() = outerAtomicCostUnits
        .groupBy { it.symbolIndex }
        .toSortedMap()
        .map { (symbolIndex, units) ->
            val first = units.first()
            PaymentCostUnitDomain(
                symbolIndex = symbolIndex,
                kind = when (first.kind) {
                    com.wingedsheep.engine.core.PaymentCostKindV1.COLORED -> PaymentCostKind.COLORED
                    com.wingedsheep.engine.core.PaymentCostKindV1.COLORLESS -> PaymentCostKind.COLORLESS
                    com.wingedsheep.engine.core.PaymentCostKindV1.GENERIC -> PaymentCostKind.GENERIC
                },
                amount = units.size,
                allowedColors = first.allowedColors,
            )
        }

internal val PaymentDomainV5.sourceActivations: List<PaymentSourceActivationDomain>
    get() = sourceActivationOptions.map { source ->
        PaymentSourceActivationDomain(
            sourceId = source.sourceId,
            sourceName = source.sourceName,
            manaAbilityKey = source.manaAbilityKey,
            productionChoices = source.productionChoices,
        )
    }

internal val PaymentDomainV5.currentPool: PaymentPoolDomainV4
    get() {
        val amounts = linkedMapOf<PaymentManaColor, Int>()
        val floating = mutableListOf<CertifiedFloatingManaBucketDomainV4>()
        initialPoolBuckets.forEach { bucket ->
            val color = when (val key = bucket.key) {
                is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> key.color
                is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.key.poolColor
            }
            amounts[color] = (amounts[color] ?: 0) + bucket.availableAmount
            val certifiedKey = bucket.key as? InitialPoolBucketKeyV1.CertifiedFloatingBucket
            if (certifiedKey != null) {
                val key = certifiedKey.key
                floating += CertifiedFloatingManaBucketDomainV4(
                    sourceId = key.sourceId,
                    poolColor = key.poolColor,
                    sourceSubtypes = key.sourceSubtypes.map(Subtype::value).sorted(),
                    amount = bucket.availableAmount,
                )
            }
        }
        return PaymentPoolDomainV4(
            white = amounts[PaymentManaColor.WHITE] ?: 0,
            blue = amounts[PaymentManaColor.BLUE] ?: 0,
            black = amounts[PaymentManaColor.BLACK] ?: 0,
            red = amounts[PaymentManaColor.RED] ?: 0,
            green = amounts[PaymentManaColor.GREEN] ?: 0,
            colorless = amounts[PaymentManaColor.COLORLESS] ?: 0,
            certifiedFloatingBuckets = floating,
        )
    }
