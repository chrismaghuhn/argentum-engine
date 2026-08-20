package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.core.PermanentAttachedEvent
import com.wingedsheep.engine.core.PermanentUnattachedEvent
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.handlers.TargetFinder
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Triggers
import com.wingedsheep.sdk.dsl.card
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * The batch returns one complete event list. Reordering the selected collection may change the
 * presentation order, but it must not change the semantic attachment trigger wave.
 */
class AttachmentBatchTriggerSemanticsTest : ScenarioTestBase() {

    private val triggeredEquipmentA = card("Test Triggered Transfer Equipment A") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}; Whenever this becomes attached, draw a card."
        equipAbility("{0}")
        triggeredAbility {
            trigger = Triggers.becomesAttached()
            effect = Effects.DrawCards(1)
        }
    }

    private val triggeredEquipmentB = card("Test Triggered Transfer Equipment B") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}; Whenever this becomes attached, draw a card."
        equipAbility("{0}")
        triggeredAbility {
            trigger = Triggers.becomesAttached()
            effect = Effects.DrawCards(1)
        }
    }

    init {
        cardRegistry.register(listOf(triggeredEquipmentA, triggeredEquipmentB))

        test("reversing collection order preserves the complete attachment trigger wave") {
            fun setup() = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, triggeredEquipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, triggeredEquipmentB.name, "Grizzly Bears")
                .withActivePlayer(1)
                .build()

            val firstGame = setup()
            val secondGame = setup()
            val firstSource = firstGame.findPermanent("Grizzly Bears")!!
            val secondSource = secondGame.findPermanent("Grizzly Bears")!!
            val firstDestination = firstGame.findPermanent("Hill Giant")!!
            val secondDestination = secondGame.findPermanent("Hill Giant")!!
            val firstA = firstGame.findPermanent(triggeredEquipmentA.name)!!
            val firstB = firstGame.findPermanent(triggeredEquipmentB.name)!!
            val secondA = secondGame.findPermanent(triggeredEquipmentA.name)!!
            val secondB = secondGame.findPermanent(triggeredEquipmentB.name)!!

            val mutation = AttachmentBatchMutation(
                AttachmentLegality(cardRegistry, TargetFinder())
            )
            val first = mutation.apply(
                state = firstGame.state,
                domainAttachments = listOf(firstA, firstB),
                orderingDomain = listOf(firstA, firstB),
                orderedAttachments = listOf(firstA, firstB),
                targetId = firstDestination,
                controllerId = firstGame.player1Id,
            )
            val second = mutation.apply(
                state = secondGame.state,
                domainAttachments = listOf(secondB, secondA),
                orderingDomain = listOf(secondA, secondB),
                orderedAttachments = listOf(secondA, secondB),
                targetId = secondDestination,
                controllerId = secondGame.player1Id,
            )

            first.error shouldBe null
            second.error shouldBe null
            first.events.filterIsInstance<PermanentUnattachedEvent>() shouldContainExactlyInAnyOrder
                listOf(
                    PermanentUnattachedEvent(firstA, triggeredEquipmentA.name, firstSource, firstGame.player1Id),
                    PermanentUnattachedEvent(firstB, triggeredEquipmentB.name, firstSource, firstGame.player1Id),
                )
            second.events.filterIsInstance<PermanentUnattachedEvent>() shouldContainExactlyInAnyOrder
                listOf(
                    PermanentUnattachedEvent(secondA, triggeredEquipmentA.name, secondSource, secondGame.player1Id),
                    PermanentUnattachedEvent(secondB, triggeredEquipmentB.name, secondSource, secondGame.player1Id),
                )

            val firstAttached = first.events.filterIsInstance<PermanentAttachedEvent>()
                .map { it.attachmentId to it.attachedToId }
                .toSet()
            val secondAttached = second.events.filterIsInstance<PermanentAttachedEvent>()
                .map { it.attachmentId to it.attachedToId }
                .toSet()
            firstAttached shouldBe setOf(firstA to firstDestination, firstB to firstDestination)
            secondAttached shouldBe setOf(secondA to secondDestination, secondB to secondDestination)

            fun triggerSignatures(state: com.wingedsheep.engine.state.GameState, events: List<com.wingedsheep.engine.core.GameEvent>) =
                TriggerDetector(cardRegistry).detectTriggers(state, events)
                    .map { trigger ->
                        Triple(
                            trigger.sourceName,
                            trigger.ability.id,
                            trigger.triggerContext.triggeringEntityId,
                        )
                    }
                    .toSet()

            val firstTriggers = triggerSignatures(first.state, first.events)
            val secondTriggers = triggerSignatures(second.state, second.events)
            firstTriggers.size shouldBe 2
            secondTriggers.size shouldBe 2
            firstTriggers.map { it.first to it.second } shouldContainExactlyInAnyOrder
                secondTriggers.map { it.first to it.second }
            firstTriggers.map { it.first } shouldContainExactlyInAnyOrder
                listOf(triggeredEquipmentA.name, triggeredEquipmentB.name)
            secondTriggers.map { it.first } shouldContainExactlyInAnyOrder
                listOf(triggeredEquipmentA.name, triggeredEquipmentB.name)
        }
    }
}
