package com.wingedsheep.mtg.sets.definitions.znr.cards

import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.GatherCardsEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter

/**
 * Skyclave Apparition
 * {1}{W}{W}
 * Creature — Kor Spirit
 * 2/2
 *
 * When this creature enters, exile up to one target nonland, nontoken permanent you don't control
 * with mana value 4 or less.
 * When this creature leaves the battlefield, the exiled card's owner creates an X/X blue Illusion
 * creature token, where X is the mana value of the exiled card.
 */
val SkyclaveApparition = card("Skyclave Apparition") {
    manaCost = "{1}{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Kor Spirit"
    power = 2
    toughness = 2
    oracleText = "When this creature enters, exile up to one target nonland, nontoken permanent " +
        "you don't control with mana value 4 or less.\n" +
        "When this creature leaves the battlefield, the exiled card's owner creates an X/X blue " +
        "Illusion creature token, where X is the mana value of the exiled card."

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        val target = target(
            "up to one target nonland, nontoken permanent you don't control with mana value 4 or less",
            TargetObject(
                optional = true,
                filter = TargetFilter(
                    baseFilter = GameObjectFilter.NonlandPermanent.nontoken().opponentControls()
                ).manaValueAtMost(4),
            ),
        )
        effect = Effects.ExileUntilLeaves(target)
    }

    triggeredAbility {
        trigger = Triggers.LeavesBattlefield
        effect = Effects.ForEachPlayer(
            players = Player.OwnersOfLinkedExile,
            effects = listOf(
                GatherCardsEffect(
                    source = CardSource.FromLinkedExile(),
                    storeAs = "exiledCard",
                ),
                Effects.CreateDynamicToken(
                    dynamicPower = DynamicAmount.StoredCardManaValue("exiledCard"),
                    dynamicToughness = DynamicAmount.StoredCardManaValue("exiledCard"),
                    colors = setOf(Color.BLUE),
                    creatureTypes = setOf("Illusion"),
                ),
            ),
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "39"
        artist = "Donato Giancola"
        imageUri = "https://cards.scryfall.io/normal/front/b/8/b83cfbaa-7890-4f6f-878b-4edb45677371.jpg?1783929406"

        ruling(
            "2020-09-25",
            "If Skyclave Apparition leaves the battlefield before its first ability resolves, " +
                "the target permanent is still exiled. No Illusion token will be created when the " +
                "ability that exiled it resolves.",
        )
        ruling(
            "2020-09-25",
            "If the exiled card has an X in its mana cost, X is 0 when determining its mana value.",
        )
    }
}
