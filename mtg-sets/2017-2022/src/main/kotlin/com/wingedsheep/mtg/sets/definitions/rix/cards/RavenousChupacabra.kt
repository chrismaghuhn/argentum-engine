package com.wingedsheep.mtg.sets.definitions.rix.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetCreature

/**
 * Ravenous Chupacabra — Rivals of Ixalan #82 (canonical printing).
 * {2}{B}{B} · Creature — Beast Horror · 2/2
 *
 * When this creature enters, destroy target creature an opponent controls.
 *
 * Oracle verified against Scryfall RIX #82 on 2026-08-17.
 */
val RavenousChupacabra = card("Ravenous Chupacabra") {
    manaCost = "{2}{B}{B}"
    colorIdentity = "B"
    typeLine = "Creature — Beast Horror"
    power = 2
    toughness = 2
    oracleText = "When this creature enters, destroy target creature an opponent controls."

    triggeredAbility {
        trigger = Triggers.EntersBattlefield
        val target = target(
            "target creature an opponent controls",
            TargetCreature(filter = TargetFilter.Creature.opponentControls()),
        )
        effect = Effects.Destroy(target)
    }

    metadata {
        rarity = Rarity.UNCOMMON
        collectorNumber = "82"
        artist = "Daarken"
        imageUri = "https://cards.scryfall.io/normal/front/0/2/02551196-ecea-472f-9547-3c9658d0489e.jpg?1783935306"
    }
}
