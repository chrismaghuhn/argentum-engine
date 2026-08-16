package com.wingedsheep.mtg.sets.definitions.znr.cards

import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Rarity
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.FeasibilityCheck
import com.wingedsheep.sdk.scripting.effects.ForEachInCollectionEffect
import com.wingedsheep.sdk.scripting.effects.MayEffect
import com.wingedsheep.sdk.scripting.effects.SuccessCriterion
import com.wingedsheep.sdk.scripting.targets.EffectTarget

/**
 * Akiri, Fearless Voyager — Zendikar Rising #220
 * {1}{R}{W}
 * Legendary Creature — Kor Warrior, 3/3
 *
 * Whenever you attack a player with one or more equipped creatures, draw a card.
 * {W}: You may unattach an Equipment from a creature you control. If you do, tap that creature
 * and it gains indestructible until end of turn.
 *
 * The activated ability uses a host-first resolution pipeline. The host is a non-targeting,
 * explicitly stored choice; the second domain is all Equipment attached to that host and does not
 * filter by Equipment controller. The named host slot is used after unattach so the follow-up never
 * attempts to reconstruct the former attachment from live state.
 */
val AkiriFearlessVoyager = card("Akiri, Fearless Voyager") {
    manaCost = "{1}{R}{W}"
    colorIdentity = "RW"
    typeLine = "Legendary Creature — Kor Warrior"
    power = 3
    toughness = 3
    oracleText = "Whenever you attack a player with one or more equipped creatures, draw a card.\n" +
        "{W}: You may unattach an Equipment from a creature you control. If you do, tap that " +
        "creature and it gains indestructible until end of turn."

    triggeredAbility {
        trigger = Triggers.YouAttackPlayerWithFilter(
            GameObjectFilter.Creature.youControl().equipped()
        )
        effect = Effects.DrawCards(1)
        description = "Whenever you attack a player with one or more equipped creatures, draw a card."
    }

    activatedAbility {
        cost = Costs.Mana("{W}")
        effect = MayEffect(
            effect = Effects.IfYouDo(
                action = Effects.Pipeline {
                    val hosts = gather(
                        GameObjectFilter.Creature.youControl().equipped(),
                        name = "hostCandidates",
                    )
                    val host = chooseExactly(
                        count = 1,
                        from = hosts,
                        prompt = "Choose a creature you control with an attached Equipment",
                        alwaysPrompt = true,
                        name = "host",
                    )
                    val equipment = gather(
                        CardSource.AttachedTo(
                            host = EffectTarget.PipelineTarget("host"),
                            filter = GameObjectFilter.Artifact.withSubtype(Subtype.EQUIPMENT),
                        ),
                        name = "equipmentCandidates",
                    )
                    val chosenEquipment = chooseExactly(
                        count = 1,
                        from = equipment,
                        prompt = "Choose an Equipment to unattach",
                        alwaysPrompt = true,
                        name = "chosenEquipment",
                    )
                    run(
                        ForEachInCollectionEffect(
                            collection = chosenEquipment.key,
                            effect = Effects.UnattachEquipment(EffectTarget.Self),
                        )
                    )
                },
                ifYouDo = Effects.Composite(
                    Effects.Tap(EffectTarget.PipelineTarget("host")),
                    Effects.GrantKeyword(
                        Keyword.INDESTRUCTIBLE,
                        EffectTarget.PipelineTarget("host"),
                        Duration.EndOfTurn,
                    ),
                ),
                successCriterion = SuccessCriterion.CollectionNonEmpty("chosenEquipment"),
            ),
            feasibility = FeasibilityCheck.ControlsPermanentMatching(
                GameObjectFilter.Creature.youControl().equipped(),
            ),
        )
        description = "{W}: You may unattach an Equipment from a creature you control. If you do, " +
            "tap that creature and it gains indestructible until end of turn."
    }

    metadata {
        rarity = Rarity.RARE
        collectorNumber = "220"
        artist = "Ekaterina Burmak"
        flavorText = "\"No angel bars our ascent. Emeria waits above!\""
        imageUri = "https://cards.scryfall.io/normal/front/6/9/69e42511-f653-4a6f-a5d4-50e21dfc8077.jpg"
        ruling(
            "2020-09-25",
            "You choose which Equipment to unattach as Akiri's second ability resolves. Other " +
                "Equipment remain attached, and no player can act between the Equipment becoming " +
                "unattached and the creature being tapped and gaining indestructible."
        )
        ruling(
            "2020-09-25",
            "The Equipment remains on the battlefield after it becomes unattached."
        )
    }
}
