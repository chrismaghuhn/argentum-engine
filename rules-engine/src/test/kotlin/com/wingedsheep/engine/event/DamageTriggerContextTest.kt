package com.wingedsheep.engine.event

import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageRecipientKind
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.TriggeredAbilityContinuation
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.core.effectiveRecipientKind
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.DynamicAmountEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.handlers.effects.composite.ReflexiveTriggerEffectExecutor
import com.wingedsheep.engine.handlers.effects.TargetResolutionUtils
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.CreatureStats
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggerBinding
import com.wingedsheep.sdk.scripting.predicates.CardPredicate
import com.wingedsheep.sdk.scripting.events.RecipientFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.values.EntityNumericProperty
import com.wingedsheep.sdk.scripting.values.EntityReference
import com.wingedsheep.sdk.scripting.values.contextScopedReferenceIn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class DamageTriggerContextTest : FunSpec({
    val json = Json {
        serializersModule = engineSerializersModule
        encodeDefaults = true
    }

    val sourceId = EntityId("damage-source")
    val recipientId = EntityId("damage-recipient")

    val damageEvent = DamageDealtEvent(
        sourceId = sourceId,
        targetId = recipientId,
        amount = 3,
        isCombatDamage = true,
        recipientKind = DamageRecipientKind.CREATURE,
        damageSourceLastKnownSnapshot = EntitySnapshot(
            entityId = sourceId,
            power = 4,
            toughness = 4,
            subtypes = setOf("Spider"),
            colors = setOf(Color.GREEN.name),
        ),
        damageRecipientLastKnownSnapshot = EntitySnapshot(
            entityId = recipientId,
            power = 2,
            toughness = 3,
            subtypes = setOf("Elf"),
            colors = setOf(Color.GREEN.name),
        )
    )

    test("damage trigger context preserves source and recipient as separate roles") {
        val context = TriggerContext.fromEvent(damageEvent)

        context.triggeringEntityId shouldBe recipientId
        context.damageSourceEntityId shouldBe sourceId
        context.damageRecipientEntityId shouldBe recipientId
        context.damageRecipientKind shouldBe DamageRecipientKind.CREATURE
    }

    test("damage source and recipient targets resolve independently of triggering entity") {
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context) shouldBe sourceId
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context) shouldBe recipientId
    }

    test("source-filtered observers keep source TriggeringEntity without losing the recipient") {
        val context = TriggerContext.fromDamageEvent(
            damageEvent,
            triggeringEntityId = sourceId,
            triggeringPlayerId = EntityId("recipient-player")
        )

        context.triggeringEntityId shouldBe sourceId
        context.triggeringPlayerId shouldBe EntityId("recipient-player")
        context.damageSourceEntityId shouldBe sourceId
        context.damageRecipientEntityId shouldBe recipientId
    }

    test("simultaneous damage events retain each source-recipient pairing") {
        val secondSourceId = EntityId("second-source")
        val secondRecipientId = EntityId("second-recipient")
        val secondEvent = damageEvent.copy(
            sourceId = secondSourceId,
            targetId = secondRecipientId,
            damageSourceLastKnownSnapshot = EntitySnapshot(secondSourceId, power = 5),
            damageRecipientLastKnownSnapshot = EntitySnapshot(secondRecipientId, power = 6)
        )

        val firstContext = TriggerContext.fromEvent(damageEvent)
        val secondContext = TriggerContext.fromEvent(secondEvent)

        firstContext.damageSourceEntityId shouldBe sourceId
        firstContext.damageRecipientEntityId shouldBe recipientId
        secondContext.damageSourceEntityId shouldBe secondSourceId
        secondContext.damageRecipientEntityId shouldBe secondRecipientId
        secondContext.damageSourceLastKnownSnapshot?.entityId shouldBe secondSourceId
        secondContext.damageRecipientLastKnownSnapshot?.entityId shouldBe secondRecipientId
    }

    test("a player damage recipient resolves through the player-target path") {
        val playerId = EntityId("damage-player")
        val playerEvent = damageEvent.copy(
            targetId = playerId,
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            damageRecipientLastKnownSnapshot = null
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = playerId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = playerId,
            damageRecipientKind = DamageRecipientKind.PLAYER
        )

        TriggerContext.fromEvent(playerEvent).damageRecipientEntityId shouldBe playerId
        TargetResolutionUtils.resolvePlayerTarget(EffectTarget.DamageRecipient, context) shouldBe playerId
    }

    test("non-player damage recipients are rejected by the player-target path") {
        listOf(
            DamageRecipientKind.CREATURE,
            DamageRecipientKind.PLANESWALKER,
            DamageRecipientKind.BATTLE,
            DamageRecipientKind.OTHER,
            DamageRecipientKind.UNKNOWN
        ).forEach { kind ->
            val context = EffectContext(
                sourceId = null,
                controllerId = EntityId("controller"),
                damageSourceEntityId = sourceId,
                damageRecipientEntityId = recipientId,
                damageRecipientKind = kind
            )

            TargetResolutionUtils.resolvePlayerTarget(EffectTarget.DamageRecipient, context) shouldBe null
        }
    }

    test("damage detector dispatch uses the captured recipient role") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val playerEvent = damageEvent.copy(
            targetId = EntityId("player"),
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            damageRecipientLastKnownSnapshot = null
        )
        val planeswalkerEvent = damageEvent.copy(
            targetId = EntityId("planeswalker"),
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.PLANESWALKER
        )

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = com.wingedsheep.sdk.scripting.events.RecipientFilter.AnyPlayer),
            playerEvent,
            GameState()
        ) shouldBe true
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = com.wingedsheep.sdk.scripting.events.RecipientFilter.AnyPlayer),
            damageEvent,
            GameState()
        ) shouldBe false
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = com.wingedsheep.sdk.scripting.events.RecipientFilter.AnyPlayerOrPlaneswalker),
            planeswalkerEvent,
            GameState()
        ) shouldBe true
    }

    test("a deathtouch source damaging a planeswalker keeps the planeswalker recipient role") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val event = damageEvent.copy(
            targetId = EntityId("planeswalker"),
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.PLANESWALKER,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot?.copy(
                keywords = setOf("DEATHTOUCH")
            )
        )

        event.effectiveRecipientKind shouldBe DamageRecipientKind.PLANESWALKER
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyPlayerOrPlaneswalker),
            event,
            GameState()
        ) shouldBe true
        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(recipient = RecipientFilter.AnyCreature),
            event,
            GameState()
        ) shouldBe false
    }

    test("damage detector dispatch rejects a non-player recipient even when its id is player-shaped") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val observer = TriggerIndex.IndexedEntity(
            entityId = EntityId("damage-observer"),
            cardComponent = CardComponent(
                cardDefinitionId = "damage-observer",
                name = "Damage Observer",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT))
            ),
            controllerId = EntityId("player"),
            abilities = listOf(
                com.wingedsheep.sdk.scripting.TriggeredAbility(
                    id = AbilityId("damage-observer-ability"),
                    trigger = EventPattern.DealsDamageEvent(recipient = RecipientFilter.You),
                    binding = TriggerBinding.ANY,
                    effect = Effects.DrawCards(1)
                )
            )
        )
        val index = TriggerIndex(
            byCategory = emptyMap(),
            aurasByTarget = emptyMap(),
            grantProviders = emptyList(),
            statics = BattlefieldStaticsIndex.EMPTY,
            damageToYouObservers = listOf(observer),
            subtypeDamageObservers = emptyList(),
            damageObservers = emptyList(),
            creatureDamageDeathTrackers = emptyList()
        )
        val detector = DamageTriggerDetector(
            TriggerAbilityResolver(CardRegistry(), AbilityRegistry()),
            matcher
        )
        val playerId = EntityId("player")
        val state = GameState()
        val playerEvent = damageEvent.copy(
            targetId = playerId,
            targetIsPlayer = true,
            recipientKind = DamageRecipientKind.PLAYER,
            damageRecipientLastKnownSnapshot = null
        )
        val creatureEvent = playerEvent.copy(
            targetIsPlayer = false,
            recipientKind = DamageRecipientKind.CREATURE
        )

        val playerTriggers = mutableListOf<PendingTrigger>()
        detector.detectDamageToControllerTriggers(
            state, playerEvent, playerTriggers, state.projectedState, index
        )
        playerTriggers.size shouldBe 1

        val creatureTriggers = mutableListOf<PendingTrigger>()
        detector.detectDamageToControllerTriggers(
            state, creatureEvent, creatureTriggers, state.projectedState, index
        )
        creatureTriggers shouldBe emptyList()
    }

    test("damage source predicates do not fall back to the recipient") {
        val matcher = TriggerMatcher(PredicateEvaluator(), ConditionEvaluator())
        val candidate = CardComponent(
            cardDefinitionId = "recipient-creature",
            name = "Recipient Creature",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
            baseStats = CreatureStats(2, 2)
        )
        val state = GameState().withEntity(recipientId, ComponentContainer.of(candidate))
        val event = damageEvent.copy(
            sourceId = EntityId("missing-source"),
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot
                ?.copy(entityId = sourceId)
        )

        matcher.matchesDealsDamageTrigger(
            EventPattern.DealsDamageEvent(
                recipient = RecipientFilter.AnyCreature,
                sourceFilter = GameObjectFilter.Creature
            ),
            event,
            state
        ) shouldBe false
    }

    test("damage-role entity properties and predicates read captured LKI") {
        val snapshot = EntitySnapshot(
            entityId = recipientId,
            power = 7,
            toughness = 4,
            subtypes = setOf("Elf"),
            colors = setOf(Color.GREEN.name)
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = snapshot
        )
        val amountEvaluator = DynamicAmountEvaluator()

        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.Power),
            context
        ) shouldBe 7
        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.SubtypeCount),
            context
        ) shouldBe 1
        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.ColorCount),
            context
        ) shouldBe 1
        amountEvaluator.evaluate(
            GameState(),
            DynamicAmount.EntityProperty(EntityReference.DamageSource, EntityNumericProperty.Power),
            context
        ) shouldBe 4

        val candidateId = EntityId("candidate")
        val candidate = CardComponent(
            cardDefinitionId = "test-candidate",
            name = "Test Candidate",
            manaCost = ManaCost(emptyList()),
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
                subtypes = setOf(Subtype("Elf"))
            ),
            baseStats = CreatureStats(6, 6),
            colors = setOf(Color.GREEN)
        )
        val state = GameState().withEntity(candidateId, ComponentContainer.of(candidate))
        val predicateContext = PredicateContext(
            controllerId = EntityId("controller"),
            damageSourceId = sourceId,
            damageRecipientId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = snapshot
        )
        val predicateEvaluator = PredicateEvaluator()

        predicateEvaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.PowerAtMostEntity(EntityReference.DamageRecipient),
            predicateContext
        ) shouldBe true
        predicateEvaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.SharesCreatureTypeWith(EntityReference.DamageRecipient),
            predicateContext
        ) shouldBe true
        predicateEvaluator.matchesCardPredicate(
            state,
            state.projectedState,
            candidateId,
            CardPredicate.SharesColorWith(EntityReference.DamageRecipient),
            predicateContext
        ) shouldBe true

        contextScopedReferenceIn(
            DynamicAmount.EntityProperty(EntityReference.DamageSource, EntityNumericProperty.Power)
        ) shouldBe "DamageSource"
        contextScopedReferenceIn(
            DynamicAmount.EntityProperty(EntityReference.DamageRecipient, EntityNumericProperty.Power)
        ) shouldBe "DamageRecipient"
    }

    test("live damage-role predicates prefer current projection over stale LKI") {
        val controllerId = EntityId("live-controller")
        val liveRecipient = CardComponent(
            cardDefinitionId = "live-recipient",
            name = "Live Recipient",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
                subtypes = setOf(Subtype("Goblin"))
            ),
            baseStats = CreatureStats(2, 2),
            colors = setOf(Color.RED)
        )
        val liveState = GameState(
            zones = mapOf(ZoneKey(controllerId, Zone.BATTLEFIELD) to listOf(recipientId))
        ).withEntity(
            recipientId,
            ComponentContainer.of(liveRecipient, ControllerComponent(controllerId))
        )
        val candidateId = EntityId("live-candidate")
        val candidate = CardComponent(
            cardDefinitionId = "live-candidate",
            name = "Live Candidate",
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(
                cardTypes = setOf(CardType.CREATURE),
                subtypes = setOf(Subtype("Elf"))
            ),
            baseStats = CreatureStats(6, 6),
            colors = setOf(Color.GREEN)
        )
        val stateWithCandidate = liveState.withEntity(
            candidateId,
            ComponentContainer.of(candidate)
        )
        val context = PredicateContext(
            controllerId = controllerId,
            damageRecipientId = recipientId,
            damageRecipientKind = DamageRecipientKind.CREATURE,
            damageRecipientLastKnownSnapshot = EntitySnapshot(
                entityId = recipientId,
                power = 7,
                subtypes = setOf("Elf"),
                colors = setOf(Color.GREEN.name)
            )
        )
        val evaluator = PredicateEvaluator()

        evaluator.matchesCardPredicate(
            stateWithCandidate,
            stateWithCandidate.projectedState,
            candidateId,
            CardPredicate.PowerAtMostEntity(EntityReference.DamageRecipient),
            context
        ) shouldBe false
        evaluator.matchesCardPredicate(
            stateWithCandidate,
            stateWithCandidate.projectedState,
            candidateId,
            CardPredicate.SharesCreatureTypeWith(EntityReference.DamageRecipient),
            context
        ) shouldBe false
        evaluator.matchesCardPredicate(
            stateWithCandidate,
            stateWithCandidate.projectedState,
            candidateId,
            CardPredicate.SharesColorWith(EntityReference.DamageRecipient),
            context
        ) shouldBe false
    }

    test("reflexive trigger context preserves both damage roles") {
        val context = EffectContext(
            sourceId = sourceId,
            controllerId = EntityId("controller"),
            triggeringEntityId = sourceId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        val event = ReflexiveTriggerEffectExecutor.buildReflexiveTriggeredEvent(
            state = GameState(),
            reflexiveEffect = Effects.DrawCards(1),
            reflexiveTargetRequirements = emptyList(),
            descriptionOverride = null,
            effectContext = context
        )

        event.carriedTriggerContext.damageSourceEntityId shouldBe sourceId
        event.carriedTriggerContext.damageRecipientEntityId shouldBe recipientId
    }

    test("damage roles survive triggered-ability continuation serialization") {
        val continuation: ContinuationFrame = TriggeredAbilityContinuation(
            decisionId = "damage-decision",
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = sourceId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.PLANESWALKER,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageEvent.damageRecipientLastKnownSnapshot
        )

        val encoded = json.encodeToString(ContinuationFrame.serializer(), continuation)
        json.decodeFromString(ContinuationFrame.serializer(), encoded) shouldBe continuation
    }

    test("damage role targets retain IDs when source and recipient leave before resolution") {
        val context = EffectContext(
            sourceId = null,
            controllerId = EntityId("controller"),
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        TargetResolutionUtils.resolveTarget(EffectTarget.DamageSource, context) shouldBe sourceId
        TargetResolutionUtils.resolveTarget(EffectTarget.DamageRecipient, context) shouldBe recipientId
    }

    test("damage source and recipient roles survive event and trigger-context serialization") {
        val eventRoundTrip = json.decodeFromString<GameEvent>(
            json.encodeToString(GameEvent.serializer(), damageEvent)
        )
        eventRoundTrip shouldBe damageEvent

        val context = TriggerContext.fromEvent(damageEvent)
        val encoded = json.encodeToString(TriggerContext.serializer(), context)
        val decoded = json.decodeFromString(TriggerContext.serializer(), encoded)

        decoded shouldBe context
        json.encodeToString(TriggerContext.serializer(), decoded) shouldBe encoded

        val damageSourceReference = json.encodeToString(
            EntityReference.serializer(), EntityReference.DamageSource
        )
        val damageRecipientReference = json.encodeToString(
            EntityReference.serializer(), EntityReference.DamageRecipient
        )
        json.decodeFromString(EntityReference.serializer(), damageSourceReference) shouldBe
            EntityReference.DamageSource
        json.decodeFromString(EntityReference.serializer(), damageRecipientReference) shouldBe
            EntityReference.DamageRecipient
    }

    test("damage roles survive serialization of the triggered ability stack component") {
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = recipientId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId
        )

        val encoded = json.encodeToString(TriggeredAbilityOnStackComponent.serializer(), ability)
        val decoded = json.decodeFromString(TriggeredAbilityOnStackComponent.serializer(), encoded)

        decoded shouldBe ability
    }

    test("damage roles survive a GameState replay serialization round trip") {
        val stackId = EntityId("damage-stack")
        val ability = TriggeredAbilityOnStackComponent(
            sourceId = sourceId,
            sourceName = "damage-observer",
            controllerId = EntityId("controller"),
            effect = Effects.DrawCards(1),
            description = "draw",
            triggeringEntityId = sourceId,
            damageSourceEntityId = sourceId,
            damageRecipientEntityId = recipientId,
            damageRecipientKind = DamageRecipientKind.PLANESWALKER,
            damageSourceLastKnownSnapshot = damageEvent.damageSourceLastKnownSnapshot,
            damageRecipientLastKnownSnapshot = damageEvent.damageRecipientLastKnownSnapshot
        )
        val state = GameState(
            entities = mapOf(stackId to ComponentContainer.of(ability)),
            stack = listOf(stackId)
        )

        val replayJson = Json {
            serializersModule = engineSerializersModule
            allowStructuredMapKeys = true
        }
        val encoded = replayJson.encodeToString(GameState.serializer(), state)
        replayJson.decodeFromString(GameState.serializer(), encoded) shouldBe state
    }
})
