package com.wingedsheep.mtg.sets.definitions.mid.cards

import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.daybound
import com.wingedsheep.sdk.dsl.nightbound
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetPermanent
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

/**
 * Outland Liberator // Frenzied Trapbreaker (MID #190)
 * {1}{G} · Creature — Human Werewolf, 2/2 // Creature — Werewolf, 3/3
 *
 * Outland Liberator — {1}, Sacrifice this creature: Destroy target artifact or enchantment.
 * Daybound.
 * Frenzied Trapbreaker — {1}, Sacrifice this creature: Destroy target artifact or enchantment.
 * Whenever this creature attacks, destroy target artifact or enchantment defending player controls.
 * Nightbound.
 */
private val OutlandLiberatorFront = card("Outland Liberator") {
    manaCost = "{1}{G}"
    colorIdentity = "G"
    typeLine = "Creature — Human Werewolf"
    power = 2
    toughness = 2
    oracleText = "{1}, Sacrifice this creature: Destroy target artifact or enchantment.\n" +
        "Daybound (If a player casts no spells during their own turn, it becomes night next turn.)"

    daybound()
    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{1}"), Costs.SacrificeSelf)
        val target = target(
            "target artifact or enchantment",
            TargetPermanent(filter = TargetFilter.ArtifactOrEnchantment),
        )
        effect = Effects.Destroy(target)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "190"
        artist = "Randy Vargas"
        flavorText = "\"Just hold still. I'll help you.\""
        imageUri = "https://cards.scryfall.io/normal/front/6/0/60e53d61-fcc3-4def-8206-052b46f62deb.jpg?1783925581"
    }
}

private val FrenziedTrapbreaker = card("Frenzied Trapbreaker") {
    manaCost = ""
    colorIdentity = "G"
    colorIndicator = "G"
    typeLine = "Creature — Werewolf"
    power = 3
    toughness = 3
    oracleText = "{1}, Sacrifice this creature: Destroy target artifact or enchantment.\n" +
        "Whenever this creature attacks, destroy target artifact or enchantment defending player " +
        "controls.\n" +
        "Nightbound (If a player casts at least two spells during their own turn, it becomes day next turn.)"

    nightbound()
    activatedAbility {
        cost = Costs.Composite(Costs.Mana("{1}"), Costs.SacrificeSelf)
        val target = target(
            "target artifact or enchantment",
            TargetPermanent(filter = TargetFilter.ArtifactOrEnchantment),
        )
        effect = Effects.Destroy(target)
    }
    triggeredAbility {
        trigger = Triggers.Attacks
        val target = target(
            "target artifact or enchantment defending player controls",
            TargetObject(
                filter = TargetFilter(
                    baseFilter = GameObjectFilter.ArtifactOrEnchantment
                        .targetPlayerControls(EffectTarget.PlayerRef(Player.DefendingPlayer)),
                ),
            ),
        )
        effect = Effects.Destroy(target)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "190"
        artist = "Randy Vargas"
        imageUri = "https://cards.scryfall.io/normal/back/6/0/60e53d61-fcc3-4def-8206-052b46f62deb.jpg?1783925581"
    }
}

val OutlandLiberator: CardDefinition = CardDefinition.doubleFacedCreature(
    frontFace = OutlandLiberatorFront,
    backFace = FrenziedTrapbreaker,
)
