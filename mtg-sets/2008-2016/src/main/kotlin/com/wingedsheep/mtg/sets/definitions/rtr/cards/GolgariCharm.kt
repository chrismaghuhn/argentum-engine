package com.wingedsheep.mtg.sets.definitions.rtr.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.RegenerateEffect
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject

/**
 * Golgari Charm
 * {B}{G}
 * Instant
 * Choose one —
 * • All creatures get -1/-1 until end of turn.
 * • Destroy target enchantment.
 * • Regenerate each creature you control.
 */
val GolgariCharm = card("Golgari Charm") {
    manaCost = "{B}{G}"
    colorIdentity = "BG"
    typeLine = "Instant"
    oracleText = "Choose one —\n• All creatures get -1/-1 until end of turn.\n• Destroy target enchantment.\n• Regenerate each creature you control."

    spell {
        modal(chooseCount = 1) {
            mode("All creatures get -1/-1 until end of turn") {
                effect = Effects.ForEachInGroup(
                    GroupFilter.AllCreatures,
                    Effects.ModifyStats(-1, -1, EffectTarget.Self)
                )
            }
            mode("Destroy target enchantment") {
                val target = target("target enchantment", TargetObject(filter = TargetFilter.Enchantment))
                effect = Effects.Destroy(target)
            }
            mode("Regenerate each creature you control") {
                effect = Effects.ForEachInGroup(
                    GroupFilter.AllCreaturesYouControl,
                    RegenerateEffect(EffectTarget.Self)
                )
            }
        }
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "164"
        artist = "Zoltan Boros"
        imageUri = "https://cards.scryfall.io/normal/front/4/8/48fce388-eefc-4234-8dd9-1260c1ba97eb.jpg?1783940339"
    }
}
