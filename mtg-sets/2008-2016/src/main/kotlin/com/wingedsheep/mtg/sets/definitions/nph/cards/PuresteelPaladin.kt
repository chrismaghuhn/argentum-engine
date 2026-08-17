package com.wingedsheep.mtg.sets.definitions.nph.cards

import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.ConditionalStaticAbility
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter

/**
 * Puresteel Paladin — New Phyrexia #20
 *
 * The Metalcraft ability grants a separate equip {0} ability. This preserves each Equipment's
 * printed equip ability rather than rewriting its cost, and lets the engine expose the choice as
 * an ordinary explicit activated-ability decision.
 */
val PuresteelPaladin = card("Puresteel Paladin") {
    manaCost = "{W}{W}"
    colorIdentity = "W"
    typeLine = "Creature — Human Knight"
    power = 2
    toughness = 2
    oracleText = "Whenever an Equipment you control enters, you may draw a card.\n" +
        "Metalcraft — Equipment you control have equip {0} as long as you control three or more artifacts."

    triggeredAbility {
        trigger = Triggers.entersBattlefield(
            filter = GameObjectFilter.Any.withSubtype("Equipment").youControl(),
            binding = TriggerBinding.ANY,
        )
        optional = true
        effect = Effects.DrawCards(1)
    }

    staticAbility {
        ability = ConditionalStaticAbility(
            ability = GrantActivatedAbility(
                ability = ActivatedAbility.equip(ManaCost.ZERO),
                filter = GroupFilter(GameObjectFilter.Any.withSubtype("Equipment").youControl()),
            ),
            condition = Conditions.YouControlAtLeast(3, GameObjectFilter.Artifact),
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "20"
        artist = "Jason Chan"
        imageUri = "https://cards.scryfall.io/normal/front/c/a/ca100248-fcd6-41ed-8d75-bcb473845edd.jpg?1783941324"
    }
}
