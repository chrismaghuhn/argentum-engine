package com.wingedsheep.mtg.sets.definitions.m10.cards

import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.effects.DrawCardsEffect
import com.wingedsheep.sdk.scripting.effects.LoseLifeEffect
import com.wingedsheep.sdk.scripting.targets.TargetPlayer

/**
 * Sign in Blood
 * {B}{B}
 * Sorcery
 * Target player draws two cards and loses 2 life.
 */
val SignInBlood = card("Sign in Blood") {
    manaCost = "{B}{B}"
    colorIdentity = "B"
    typeLine = "Sorcery"
    oracleText = "Target player draws two cards and loses 2 life."
    spell {
        val targetPlayer = target("target player", TargetPlayer())
        effect = Effects.Composite(
            DrawCardsEffect(2, targetPlayer),
            LoseLifeEffect(2, targetPlayer)
        )
    }
    metadata {
        rarity = Rarity.COMMON
        collectorNumber = "112"
        artist = "Howard Lyon"
        imageUri = "https://cards.scryfall.io/normal/front/1/9/1975ed97-acb8-4bb6-804a-e5da725d876e.jpg?1783942379"
    }
}
