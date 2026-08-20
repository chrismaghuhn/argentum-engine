package com.wingedsheep.mtg.sets.definitions.dmu.cards

import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Patterns
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.GrantKeywordAbility
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.values.DynamicAmount

/**
 * Astor, Bearer of Blades
 * {2}{R}{W}
 * Legendary Creature — Human Warrior
 * 4/4
 *
 * When Astor enters, look at the top seven cards of your library. You may reveal an Equipment or
 * Vehicle card from among them and put it into your hand. Put the rest on the bottom of your
 * library in a random order.
 * Equipment you control have equip {1}.
 * Vehicles you control have crew 1.
 */
val AstorBearerOfBlades = card("Astor, Bearer of Blades") {
    manaCost = "{2}{R}{W}"
    colorIdentity = "RW"
    typeLine = "Legendary Creature — Human Warrior"
    oracleText = "When Astor enters, look at the top seven cards of your library. You may reveal an Equipment or Vehicle card from among them and put it into your hand. Put the rest on the bottom of your library in a random order.\n" +
        "Equipment you control have equip {1}.\n" +
        "Vehicles you control have crew 1."
    power = 4
    toughness = 4

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        effect = Patterns.Library.lookAtTopRevealMatchingToHand(
            count = DynamicAmount.Fixed(7),
            filter = GameObjectFilter.Artifact.withAnySubtype("Equipment", "Vehicle"),
            prompt = "You may reveal an Equipment or Vehicle card to put into your hand"
        )
    }

    // This grants a second, real equip ability; it does not reduce or rewrite printed equip costs.
    staticAbility {
        ability = GrantActivatedAbility(
            ability = ActivatedAbility.equip(ManaCost.parse("{1}")),
            filter = GroupFilter(GameObjectFilter.Artifact.withSubtype("Equipment").youControl())
        )
    }

    staticAbility {
        ability = GrantKeywordAbility(
            ability = KeywordAbility.crew(1),
            filter = GroupFilter(GameObjectFilter.Artifact.withSubtype("Vehicle").youControl())
        )
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "194"
        artist = "Josh Hass"
        imageUri = "https://cards.scryfall.io/normal/front/4/0/40f3a0ba-b917-488b-adbf-60a0d3c58a56.jpg?1783921288"
        ruling("2022-09-09", "You may still activate any other equip or crew abilities that permanent has if you wish.")
        ruling("2022-09-09", "Once either the equip {1} or crew 1 ability is activated, causing Astor, Bearer of Blades to leave the battlefield won't stop the ability from resolving.")
    }
}
