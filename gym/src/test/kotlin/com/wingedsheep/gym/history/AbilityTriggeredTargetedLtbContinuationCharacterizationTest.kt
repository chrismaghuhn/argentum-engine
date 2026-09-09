package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.AbilityTriggeredEvent
import com.wingedsheep.engine.core.AbilityTriggeredSourceEndpointAuthority
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.event.TriggerProcessor
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.CommittedPerspectiveEventSource
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val targetedLtbContinuationSource = CardDefinition.creature(
    name = "Targeted LTB Continuation Source",
    manaCost = ManaCost.ZERO,
    subtypes = setOf(Subtype("Test")),
    power = 1,
    toughness = 1,
    oracleText = "When this creature dies, destroy target creature.",
    script = CardScript.creature(
        TriggeredAbility.create(
            trigger = EventPattern.ZoneChangeEvent(
                from = Zone.BATTLEFIELD,
                to = Zone.GRAVEYARD,
            ),
            binding = TriggerBinding.SELF,
            effect = Effects.Destroy(EffectTarget.ContextTarget(0)),
            targetRequirement = Targets.Creature,
        )
    ),
)

/**
 * RED characterization for a targeted leaves-the-battlefield trigger that pauses before its
 * ability is put on the stack.
 *
 * The trigger's source is deliberately absent from the post-leave state, as it is for a source
 * whose object has ceased to exist after leaving the battlefield. The original transition still
 * carries a source incarnation witness through the ZoneChangeEvent's LKI. The current
 * TriggeredAbilityContinuation preserves only the BEFORE endpoint label, so the resumed
 * AbilityTriggeredEvent is emitted in a later transition whose before-state has no source witness;
 * History-C therefore rejects the event instead of using the original event-time witness.
 */
class AbilityTriggeredTargetedLtbContinuationCharacterizationTest : FunSpec({
    fun driver(): GameTestDriver = GameTestDriver().apply {
        registerCards(TestCards.all + targetedLtbContinuationSource)
        initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            startingLife = 20,
        )
        passPriorityUntil(Step.PRECOMBAT_MAIN)
    }

    test("targeted LTB trigger loses its original BEFORE witness across pause and resume") {
        val driver = driver()
        val sourceId = driver.putCreatureOnBattlefield(
            driver.player1,
            targetedLtbContinuationSource.name,
        )
        val targetId = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val beforeLeave = driver.state
        val sourceStamp = checkNotNull(beforeLeave.objectIdentityStamps[sourceId])

        // Model the committed LTB transition after the source object has ceased to exist. The
        // event-time LKI is the authoritative witness for the original incarnation.
        val afterLeave = beforeLeave
            .removeFromZone(ZoneKey(driver.player1, Zone.BATTLEFIELD), sourceId)
            .withoutEntity(sourceId)
        val leaveEvent = ZoneChangeEvent(
            entityId = sourceId,
            entityName = targetedLtbContinuationSource.name,
            fromZone = Zone.BATTLEFIELD,
            toZone = Zone.GRAVEYARD,
            ownerId = driver.player1,
            lastKnown = EntitySnapshot(
                entityId = sourceId,
                controllerId = driver.player1,
                typeLine = TypeLine.parse("Creature"),
                cardDefinitionId = targetedLtbContinuationSource.name,
                objectIncarnationStamp = sourceStamp,
            ),
        )
        leaveEvent.lastKnown?.entityId shouldBe sourceId
        leaveEvent.lastKnown?.objectIncarnationStamp shouldBe sourceStamp

        val pending = TriggerDetector(driver.cardRegistry)
            .detectTriggers(afterLeave, listOf(leaveEvent))
            .single { it.sourceId == sourceId }
        pending.triggerContext.triggeringEntityEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT

        val paused = TriggerProcessor(
            cardRegistry = driver.cardRegistry,
            stackResolver = StackResolver(driver.cardRegistry),
        ).processTriggers(afterLeave, listOf(pending))
        val decision = paused.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        val continuation = paused.newState.peekContinuation()
            .shouldBeInstanceOf<com.wingedsheep.engine.core.TriggeredAbilityContinuation>()
        continuation.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT

        // The source existed with the original witness at trigger time, but it is absent from the
        // state held by the paused/resumed transition. This is the missing continuation payload.
        beforeLeave.hasEntity(sourceId) shouldBe true
        paused.newState.hasEntity(sourceId) shouldBe false

        driver.replaceState(paused.newState)
        val resumeBefore = driver.state
        val resumed = driver.submitTargetSelection(driver.player1, listOf(targetId))
        resumed.isSuccess shouldBe true
        val triggered = resumed.events.filterIsInstance<AbilityTriggeredEvent>().single()
        triggered.sourceEndpointAuthority shouldBe
            AbilityTriggeredSourceEndpointAuthority.BEFORE_OBJECT
        resumed.events.map { it::class.simpleName } shouldBe listOf(
            "DecisionSubmittedEvent",
            "AbilityTriggeredEvent",
            "CommitCrimeEvent",
            "TargetsChosenEvent",
            "BecomesTargetEvent",
        )

        // The resumed event is now a real committed Rules transition. History-C only sees this
        // transition and therefore cannot reconstruct the original source incarnation.
        val committed = CommittedRulesTransition(
            beforeState = resumeBefore,
            afterState = resumed.newState,
            events = resumed.events,
            sourceStepCount = 1,
        )
        val source = CommittedPerspectiveEventSource(driver.cardRegistry)
        source.capture(committed)
        val historyResult = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "targeted-ltb-continuation-characterization",
            perspectivePlayerId = driver.player1,
            registry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "targeted-ltb-continuation-characterization",
                perspectivePlayerId = driver.player1,
            ),
        )
        val accepted = historyResult
            .shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val abilityTriggeredOrdinal = accepted.evidence.eventBatch.entries.indexOfFirst {
            it.eventFamily == PerspectiveEventFamily.ABILITY_TRIGGERED
        }
        val abilityCandidate = accepted.evidence.candidates.single {
            it.slot.eventOrdinal == abilityTriggeredOrdinal
        }
        abilityCandidate.beforeWitness?.objectIdentityStamp shouldBe sourceStamp
        abilityCandidate.afterWitness shouldBe null
        abilityCandidate.witnessProvenance shouldBe HistoryCReferenceWitnessProvenance.EVENT_OWNED
    }
})
