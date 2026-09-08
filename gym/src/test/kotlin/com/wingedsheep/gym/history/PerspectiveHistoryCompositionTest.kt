package com.wingedsheep.gym.history

import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.core.AbilityActivatedEvent
import com.wingedsheep.engine.core.BlockersDeclaredEvent
import com.wingedsheep.engine.core.CountersAddedEvent
import com.wingedsheep.engine.core.DamageAssignedEvent
import com.wingedsheep.engine.core.CreatureDestroyedEvent
import com.wingedsheep.engine.core.DeclaredAttack
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.KeywordGrantedEvent
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PermanentAttachedEvent
import com.wingedsheep.engine.core.PermanentUnattachedEvent
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.gym.CommittedRulesTransition
import com.wingedsheep.gym.AutomaticHistoryCReferenceProjectionResult
import com.wingedsheep.gym.contract.PerspectiveEventBatchV1
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventV1
import com.wingedsheep.gym.contract.PerspectiveHistoryIdentityDisclosureV1
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class PerspectiveHistoryCompositionTest : FunSpec({

    val historyCastSpell = card("History D Reachable Cast") {
        manaCost = "{R}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val p1 = EntityId("p1")
    val p2 = EntityId("p2")
    val spell = EntityId("spell")
    val firstReveal = EntityId("first-reveal")
    val secondReveal = EntityId("second-reveal")

    fun registry(playerId: com.wingedsheep.sdk.model.EntityId) =
        PerspectiveAliasRegistryV1(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = playerId,
        )

    fun cardState(
        entities: Map<EntityId, ComponentContainer>,
        zones: Map<ZoneKey, List<EntityId>> = emptyMap(),
        stack: List<EntityId> = emptyList(),
        stamps: Map<EntityId, Long>,
    ) = GameState(
        entities = entities,
        zones = zones,
        stack = stack,
        turnOrder = listOf(p1, p2),
        objectIdentityStamps = stamps,
    )

    fun cardContainer(owner: EntityId, definition: String) = ComponentContainer.of(
        CardComponent(
            cardDefinitionId = definition,
            name = definition,
            manaCost = ManaCost.ZERO,
            typeLine = TypeLine(cardTypes = setOf(CardType.LAND)),
            ownerId = owner,
        ),
    )

    test("HISTD-03/HISTD-09 real multi-look composes only for the authorized perspective") {
        val driver = GameTestDriver().apply {
            registerCards(PortalSet.cards)
            registerCards(PortalSet.basicLands)
        }
        val deck = Deck.of("Island" to 20, "Forest" to 20)
        val players = driver.initMultiplayer(
            decks = listOf(deck, deck, deck),
            skipMulligans = true,
            startingPlayer = 0,
        )
        val viewer = players[0]
        val owner = players[1]
        val unrelated = players[2]
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val lookedCardA = driver.putCardInHand(owner, "Forest")
        val lookedCardB = driver.putCardInHand(owner, "Island")
        driver.replaceState(
            driver.state.copy(
                zones = driver.state.zones + (ZoneKey(owner, com.wingedsheep.sdk.core.Zone.HAND) to
                    listOf(lookedCardA, lookedCardB)),
            ),
        )
        val thief = driver.putCardInHand(viewer, "Ingenious Thief")
        driver.giveMana(viewer, Color.BLUE, 2)
        driver.castSpell(viewer, thief).isSuccess shouldBe true

        var lookTransition: CommittedRulesTransition? = null
        repeat(40) {
            if (lookTransition != null) return@repeat
            if (driver.state.pendingDecision != null) {
                driver.submitTargetSelection(viewer, listOf(owner))
            } else {
                val priority = driver.state.priorityPlayerId ?: return@repeat
                val before = driver.state
                val result = driver.passPriority(priority)
                if (result.events.any { it is HandLookedAtEvent }) {
                    lookTransition = CommittedRulesTransition(
                        beforeState = before,
                        afterState = driver.state,
                        events = result.events.filterIsInstance<HandLookedAtEvent>(),
                        sourceStepCount = 1,
                    )
                }
            }
        }
        val transition = checkNotNull(lookTransition)
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(driver.cardRegistry)
        source.capture(transition)
        val lifecycle = PerspectiveHistoryStateV1.start(
            semanticEpisodeId = "episode-history",
            playerIds = players,
        )

        val viewerProjection = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = viewer,
            registry = registry(viewer),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val viewerHistory = PerspectiveHistoryComposerV1.append(
            state = lifecycle,
            eventBatch = viewerProjection.evidence.eventBatch,
            evidence = viewerProjection.evidence,
            projection = viewerProjection.projection,
        )
        val viewerEntry = viewerHistory.histories.getValue(viewer).entries.single()
        viewerEntry.references shouldHaveSize 2
        viewerEntry.references.map { it.cardDefinitionId } shouldBe listOf("Forest", "Island")
        viewerEntry.references.map { it.semanticAlias } shouldBe listOf("o0", "o1")

        val reversedSource = com.wingedsheep.gym.CommittedPerspectiveEventSource(driver.cardRegistry)
        val reversedTransition = transition.copy(
            events = transition.events.map { event ->
                (event as HandLookedAtEvent).copy(cardIds = event.cardIds.reversed())
            },
        )
        reversedSource.capture(reversedTransition)
        val reversedProjection = reversedSource.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = viewer,
            registry = registry(viewer),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val reversedHistory = PerspectiveHistoryComposerV1.append(
            state = lifecycle,
            eventBatch = reversedProjection.evidence.eventBatch,
            evidence = reversedProjection.evidence,
            projection = reversedProjection.projection,
        )
        reversedHistory.histories.getValue(viewer).entries.single().references.map {
            it.cardDefinitionId to it.semanticAlias
        } shouldBe viewerEntry.references.map { it.cardDefinitionId to it.semanticAlias }

        val unrelatedProjection = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = unrelated,
            registry = registry(unrelated),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val unrelatedHistory = PerspectiveHistoryComposerV1.append(
            state = lifecycle,
            eventBatch = unrelatedProjection.evidence.eventBatch,
            evidence = unrelatedProjection.evidence,
            projection = unrelatedProjection.projection,
        )
        unrelatedHistory.histories.getValue(unrelated).entries.shouldBeEmpty()

        transition.afterState.getEntity(lookedCardA)
            ?.get<RevealedToComponent>()?.isRevealedTo(viewer) shouldBe true
        transition.afterState.getEntity(lookedCardA)
            ?.get<RevealedToComponent>()?.isRevealedTo(unrelated) shouldBe false
    }

    test("HISTD reference matrix binds an emitted spell object to its exact witness") {
        val state = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                spell to cardContainer(p1, "Mountain"),
            ),
            stack = listOf(spell),
            stamps = mapOf(spell to 1L),
        )
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(
            CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
            },
        )
        source.capture(
            CommittedRulesTransition(
                beforeState = state,
                afterState = state,
                events = listOf(
                    SpellCastEvent(
                        spellEntityId = spell,
                        cardName = "Mountain",
                        casterId = p1,
                    ),
                ),
                sourceStepCount = 1,
            ),
        )

        val result = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = p1,
            registry = registry(p1),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()

        result.projection.referenceOccurrences shouldHaveSize 1
        result.projection.referenceOccurrences.single().candidateIndex shouldBe 0
    }

    test("HISTD public reveal aliases are independent of raw cardIds order") {
        fun state() = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                firstReveal to cardContainer(p1, "Mountain"),
                secondReveal to cardContainer(p1, "Forest"),
            ),
            zones = mapOf(
                ZoneKey(p1, Zone.BATTLEFIELD) to listOf(firstReveal, secondReveal),
            ),
            stamps = mapOf(firstReveal to 1L, secondReveal to 1L),
        )

        fun historyFor(cardIds: List<EntityId>): List<Pair<String?, String>> {
            val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(
                CardRegistry().apply {
                    register(PortalSet.cards)
                    register(PortalSet.basicLands)
                },
            )
            val current = state()
            source.capture(
                CommittedRulesTransition(
                    beforeState = current,
                    afterState = current,
                    events = listOf(
                        CardsRevealedEvent(
                            revealingPlayerId = p1,
                            cardIds = cardIds,
                            cardNames = cardIds.map { id ->
                                if (id == firstReveal) "Mountain" else "Forest"
                            },
                        ),
                    ),
                    sourceStepCount = 1,
                ),
            )
            val projected = source.lastCommittedAutomaticReferenceProjection(
                semanticEpisodeId = "episode-history",
                perspectivePlayerId = p1,
                registry = registry(p1),
            ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
            val lifecycle = PerspectiveHistoryStateV1.start("episode-history", listOf(p1, p2))
            return PerspectiveHistoryComposerV1.append(
                state = lifecycle,
                eventBatch = projected.evidence.eventBatch,
                evidence = projected.evidence,
                projection = projected.projection,
            ).histories.getValue(p1).entries.single().references.map {
                it.cardDefinitionId to it.semanticAlias
            }
        }

        historyFor(listOf(firstReveal, secondReveal)) shouldBe
            historyFor(listOf(secondReveal, firstReveal))
    }

    test("HISTD object-bearing combat, attachment, and counter families retain C references") {
        val attacker = EntityId("attacker")
        val blocker = EntityId("blocker")
        val attachment = EntityId("attachment")
        val target = EntityId("target")
        val state = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                attacker to cardContainer(p1, "Mountain"),
                blocker to cardContainer(p2, "Forest"),
                attachment to cardContainer(p1, "Island"),
                target to cardContainer(p1, "Swamp"),
            ),
            zones = mapOf(
                ZoneKey(p1, Zone.BATTLEFIELD) to listOf(attacker, attachment, target),
                ZoneKey(p2, Zone.BATTLEFIELD) to listOf(blocker),
            ),
            stamps = mapOf(
                attacker to 1L,
                blocker to 1L,
                attachment to 1L,
                target to 1L,
            ),
        )
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(
            CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
            },
        )
        source.capture(
            CommittedRulesTransition(
                beforeState = state,
                afterState = state,
                events = listOf(
                    PermanentAttachedEvent(
                        attachmentId = attachment,
                        attachmentName = "Island",
                        attachedToId = target,
                        controllerId = p1,
                    ),
                    CountersAddedEvent(
                        entityId = target,
                        counterType = "+1/+1",
                        amount = 1,
                        placedBy = p1,
                    ),
                    AttackersDeclaredEvent(
                        attackers = listOf(attacker),
                        attackingPlayerId = p1,
                        declaredAttacks = listOf(
                            DeclaredAttack(
                                attackerId = attacker,
                                defenderId = blocker,
                                defendingPlayerId = p2,
                            ),
                        ),
                    ),
                    BlockersDeclaredEvent(
                        blockers = mapOf(blocker to listOf(attacker)),
                    ),
                    DamageAssignedEvent(
                        attackerId = attacker,
                        assignments = mapOf(blocker to 1),
                    ),
                ),
                sourceStepCount = 1,
            ),
        )
        val projected = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = p1,
            registry = registry(p1),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val history = PerspectiveHistoryComposerV1.append(
            state = PerspectiveHistoryStateV1.start("episode-history", listOf(p1, p2)),
            eventBatch = projected.evidence.eventBatch,
            evidence = projected.evidence,
            projection = projected.projection,
        ).histories.getValue(p1)

        history.entries.filter { it.references.isNotEmpty() }.size shouldBe 5
        history.entries
            .single { it.eventFamily == PerspectiveEventFamily.ATTACKERS_DECLARED }
            .relations
            .single()
            .targetAlias shouldNotBe null

        fun historyFor(event: GameEvent): String {
            val eventSource = com.wingedsheep.gym.CommittedPerspectiveEventSource(
                CardRegistry().apply {
                    register(PortalSet.cards)
                    register(PortalSet.basicLands)
                },
            )
            eventSource.capture(
                CommittedRulesTransition(
                    beforeState = state,
                    afterState = state,
                    events = listOf(event),
                    sourceStepCount = 1,
                ),
            )
            val result = eventSource.lastCommittedAutomaticReferenceProjection(
                semanticEpisodeId = "episode-history",
                perspectivePlayerId = p1,
                registry = registry(p1),
            ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
            return PerspectiveHistoryComposerV1.append(
                state = PerspectiveHistoryStateV1.start("episode-history", listOf(p1, p2)),
                eventBatch = result.evidence.eventBatch,
                evidence = result.evidence,
                projection = result.projection,
            ).histories.getValue(p1).canonicalJson()
        }

        historyFor(AttackersDeclaredEvent(listOf(attacker, blocker), attackingPlayerId = p1)) shouldBe
            historyFor(AttackersDeclaredEvent(listOf(blocker, attacker), attackingPlayerId = p1))
        historyFor(
            BlockersDeclaredEvent(
                blockers = linkedMapOf(blocker to listOf(attacker), target to listOf(attachment)),
            ),
        ) shouldBe historyFor(
            BlockersDeclaredEvent(
                blockers = linkedMapOf(target to listOf(attachment), blocker to listOf(attacker)),
            ),
        )
        historyFor(
            DamageAssignedEvent(
                attackerId = attacker,
                assignments = linkedMapOf(blocker to 1, target to 2),
            ),
        ) shouldBe historyFor(
            DamageAssignedEvent(
                attackerId = attacker,
                assignments = linkedMapOf(target to 2, blocker to 1),
            ),
        )
        historyFor(
            BlockersDeclaredEvent(
                blockers = linkedMapOf(blocker to listOf(attacker), target to listOf(attachment)),
            ),
        ) shouldNotBe historyFor(
            BlockersDeclaredEvent(
                blockers = linkedMapOf(blocker to listOf(attachment), target to listOf(attacker)),
            ),
        )
        historyFor(
            DamageAssignedEvent(
                attackerId = attacker,
                assignments = linkedMapOf(blocker to 1, target to 4),
            ),
        ) shouldNotBe historyFor(
            DamageAssignedEvent(
                attackerId = attacker,
                assignments = linkedMapOf(blocker to 4, target to 1),
            ),
        )
    }

    test("HISTD-REVIEW-INC-01 destruction and unattach bind the BEFORE incarnation") {
        val moving = EntityId("moving")
        val attachment = EntityId("attachment")
        val host = EntityId("host")
        val before = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                moving to cardContainer(p1, "Mountain"),
                attachment to cardContainer(p1, "Island"),
                host to cardContainer(p1, "Forest"),
            ),
            zones = mapOf(
                ZoneKey(p1, Zone.BATTLEFIELD) to listOf(moving, attachment, host),
            ),
            stamps = mapOf(moving to 41L, attachment to 41L, host to 41L),
        )
        val after = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                moving to cardContainer(p1, "Mountain"),
                attachment to cardContainer(p1, "Island"),
                host to cardContainer(p1, "Forest"),
            ),
            zones = mapOf(
                ZoneKey(p1, Zone.BATTLEFIELD) to listOf(attachment),
                ZoneKey(p1, Zone.GRAVEYARD) to listOf(moving, host),
            ),
            stamps = mapOf(moving to 42L, attachment to 41L, host to 42L),
        )
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(
            CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
            },
        )
        source.capture(
            CommittedRulesTransition(
                beforeState = before,
                afterState = after,
                events = listOf(
                    CreatureDestroyedEvent(
                        entityId = moving,
                        name = "Mountain",
                        reason = "test",
                        controllerId = p1,
                    ),
                    ZoneChangeEvent(
                        entityId = moving,
                        entityName = "Mountain",
                        fromZone = Zone.BATTLEFIELD,
                        toZone = Zone.GRAVEYARD,
                        ownerId = p1,
                    ),
                    PermanentUnattachedEvent(
                        attachmentId = attachment,
                        attachmentName = "Island",
                        attachedToId = host,
                        controllerId = p1,
                    ),
                ),
                sourceStepCount = 1,
            ),
        )
        val projected = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = p1,
            registry = registry(p1),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()
        val history = PerspectiveHistoryComposerV1.append(
            state = PerspectiveHistoryStateV1.start("episode-history", listOf(p1, p2)),
            eventBatch = projected.evidence.eventBatch,
            evidence = projected.evidence,
            projection = projected.projection,
        ).histories.getValue(p1)

        history.entries[0].references.single().semanticAlias shouldBe "o0"
        history.entries[1].references.map { it.semanticAlias } shouldBe listOf("o0", "o1")
        history.entries[2].references.map { it.semanticAlias } shouldBe listOf("o2", "o3")
    }

    test("HISTD-REVIEW-INC-02 activation binds the pre-cost source incarnation") {
        val sourceId = EntityId("sacrifice-source")
        val before = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                sourceId to cardContainer(p1, "Mountain"),
            ),
            zones = mapOf(ZoneKey(p1, Zone.BATTLEFIELD) to listOf(sourceId)),
            stamps = mapOf(sourceId to 41L),
        )
        val after = cardState(
            entities = mapOf(
                p1 to ComponentContainer.EMPTY,
                p2 to ComponentContainer.EMPTY,
                sourceId to cardContainer(p1, "Mountain"),
            ),
            zones = mapOf(ZoneKey(p1, Zone.GRAVEYARD) to listOf(sourceId)),
            stamps = mapOf(sourceId to 42L),
        )
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(
            CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
            },
        )
        source.capture(
            CommittedRulesTransition(
                beforeState = before,
                afterState = after,
                events = listOf(
                    AbilityActivatedEvent(
                        sourceId = sourceId,
                        sourceName = "Mountain",
                        controllerId = p1,
                    ),
                ),
                sourceStepCount = 1,
            ),
        )
        val projected = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = p1,
            registry = registry(p1),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Accepted>()

        projected.evidence.candidates.single().beforeWitness shouldBe
            HistoryCObjectWitness(sourceId, 41L)
        projected.evidence.candidates.single().afterWitness shouldBe null
        projected.projection.nextRegistry.activeBindings.keys shouldBe emptySet()
        projected.projection.incarnationRelations shouldBe emptyList()
    }

    test("HISTD reachable history-enabled cast and payment path appends successfully") {
        val cardRegistry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(historyCastSpell)
        }
        val environment = com.wingedsheep.gym.GameEnvironment.create(cardRegistry)
        val gym = com.wingedsheep.gym.GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(
                cardRegistry = cardRegistry,
            ),
        )
        gym.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of(historyCastSpell.name to 1, "Mountain" to 3)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 4)),
                ),
                startingHandSize = 4,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
            semanticEpisodeId = "episode-history",
        )

        var observed = gym.observe()
        var playLand = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        var setupSteps = 0
        while (playLand == null && setupSteps++ < 20) {
            val pass = observed.observation.legalActions.first { it.kind == "PassPriority" }
            observed = gym.step(pass.actionId)
            playLand = observed.observation.legalActions.firstOrNull { it.kind == "PlayLand" }
        }
        val selectedLand = checkNotNull(playLand)
        observed = gym.step(selectedLand.actionId)

        var cast = observed.observation.legalActions.firstOrNull {
            it.kind == "CastSpell" && it.description.contains(historyCastSpell.name)
        }
        setupSteps = 0
        while (cast == null && setupSteps++ < 20) {
            val pass = observed.observation.legalActions.first { it.kind == "PassPriority" }
            observed = gym.step(pass.actionId)
            cast = observed.observation.legalActions.firstOrNull {
                it.kind == "CastSpell" && it.description.contains(historyCastSpell.name)
            }
        }
        val selectedCast = checkNotNull(cast)
        val paymentDomain = checkNotNull(selectedCast.paymentDomain)
        val paymentPlan = checkNotNull(com.wingedsheep.gym.paymentPlanV3FromPublic(paymentDomain))
        val payload = buildJsonObject {
            selectedCast.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                Json.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(paymentPlan),
                ),
            )
        }

        gym.step(selectedCast.actionId, payload)
        gym.perspectiveHistory(environment.playerIds.first()).entries.any {
            it.eventFamily == PerspectiveEventFamily.SPELL_CAST
        } shouldBe true
    }

    test("HISTD C rejection leaves the integrated history prefix unchanged") {
        val cardRegistry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
        }
        val environment = com.wingedsheep.gym.GameEnvironment.create(cardRegistry)
        val gym = com.wingedsheep.gym.GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(
                cardRegistry = cardRegistry,
            ),
        )
        gym.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 4)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 4)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
            ),
            semanticEpisodeId = "episode-history",
        )
        val player = environment.playerIds.first()
        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)
        val before = gym.perspectiveHistory(player).canonicalJson()
        val forged = HistoryCReferenceEnvelopeV1(
            perspectivePlayerId = player,
            candidates = listOf(
                HistoryCReferenceCandidateV1(
                    slot = HistoryCReferenceSlot(
                        eventOrdinal = 0,
                        role = HistoryCReferenceSlotRole.EVENT_SUBJECT,
                    ),
                    referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
                    afterWitness = HistoryCObjectWitness(EntityId("forged"), 1L),
                    orderProof = HistoryCOrderProof(
                        authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                        rank = 0,
                    ),
                    semanticDescriptor = kotlinx.serialization.json.buildJsonObject {
                        put("type", "object_reference")
                    },
                ),
            ),
        )
        gym.lastCommittedReferenceProjection(player, forged)
            .shouldBeInstanceOf<PerspectiveReferenceProjectionResult.Rejected>()
        gym.perspectiveHistory(player).canonicalJson() shouldBe before
    }

    test("HISTD-05 incomplete History-A projection fails closed before composition") {
        val perspective = com.wingedsheep.sdk.model.EntityId("p1")
        val source = com.wingedsheep.gym.CommittedPerspectiveEventSource(CardRegistry())
        source.capture(
            CommittedRulesTransition(
                beforeState = GameState(),
                afterState = GameState(),
                events = listOf(
                    KeywordGrantedEvent(
                        targetId = com.wingedsheep.sdk.model.EntityId("card"),
                        targetName = "hidden",
                        keyword = "hidden",
                        sourceName = "effect",
                    ),
                ),
                sourceStepCount = 1,
            ),
        )

        val result = source.lastCommittedAutomaticReferenceProjection(
            semanticEpisodeId = "episode-history",
            perspectivePlayerId = perspective,
            registry = registry(perspective),
        ).shouldBeInstanceOf<AutomaticHistoryCReferenceProjectionResult.Rejected>()
        result.failure.code shouldBe HistoryCFailureCode.HISTORY_A_PROJECTION_INCOMPLETE
    }

    test("HISTD-07/HISTD-08 identity upgrades do not rewrite prefixes or link incarnations") {
        val perspective = com.wingedsheep.sdk.model.EntityId("p1")
        val firstWitness = HistoryCObjectWitness(com.wingedsheep.sdk.model.EntityId("card"), 1L)
        val secondWitness = HistoryCObjectWitness(com.wingedsheep.sdk.model.EntityId("card"), 2L)
        val state = PerspectiveHistoryStateV1.start("episode-history", listOf(perspective))
        fun evidence(witness: HistoryCObjectWitness, known: Boolean): HistoryCReferenceEvidenceV1 =
            HistoryCReferenceEvidenceV1(
                perspectivePlayerId = perspective,
                eventBatch = PerspectiveEventBatchV1(
                    perspectivePlayerId = perspective,
                    entries = listOf(
                        PerspectiveEventV1(
                            perspectiveEventOrdinal = 0,
                            eventFamily = PerspectiveEventFamily.ZONE_CHANGED,
                            semanticPayload = kotlinx.serialization.json.buildJsonObject {
                                put("type", "zone_changed")
                                put("ownerRole", "SELF")
                                put("fromZone", "HAND")
                                put("toZone", "BATTLEFIELD")
                            },
                        ),
                    ),
                ),
                candidates = listOf(
                    HistoryCReferenceCandidateV1(
                        slot = HistoryCReferenceSlot(
                            eventOrdinal = 0,
                            role = HistoryCReferenceSlotRole.MOVED_OBJECT,
                        ),
                        referenceKind = HistoryCReferenceKind.CARD_OR_RULES_OBJECT,
                        afterWitness = witness,
                        identityDisclosure = if (known) {
                            HistoryCIdentityDisclosure.DEFINITION_KNOWN
                        } else {
                            HistoryCIdentityDisclosure.OPAQUE
                        },
                        cardDefinitionId = "Forest".takeIf { known },
                        orderProof = HistoryCOrderProof(
                            authority = HistoryCOrderAuthority.EXPLICIT_PRODUCER_ORDER,
                            rank = 0,
                        ),
                        semanticDescriptor = kotlinx.serialization.json.buildJsonObject {
                            put("type", "object_reference")
                        },
                    ),
                ),
            )
        fun projection(
            alias: String,
            disclosure: HistoryCIdentityDisclosure,
            definition: String?,
        ) = PerspectiveReferenceProjectionV1(
            nextRegistry = PerspectiveAliasRegistryV1(
                semanticEpisodeId = "episode-history",
                perspectivePlayerId = perspective,
            ),
            referenceOccurrences = listOf(
                PerspectiveAliasAssignment(
                    candidateIndex = 0,
                    alias = PerspectiveSemanticAlias(alias.removePrefix("o").toLong()),
                    identityDisclosure = disclosure,
                    cardDefinitionId = definition,
                ),
            ),
            incarnationRelations = emptyList(),
        )

        val first = PerspectiveHistoryComposerV1.append(
            state = state,
            eventBatch = evidence(firstWitness, known = false).eventBatch,
            evidence = evidence(firstWitness, known = false),
            projection = projection("o0", HistoryCIdentityDisclosure.OPAQUE, null),
        )
        val second = PerspectiveHistoryComposerV1.append(
            state = first,
            eventBatch = evidence(firstWitness, known = true).eventBatch,
            evidence = evidence(firstWitness, known = true),
            projection = projection("o0", HistoryCIdentityDisclosure.DEFINITION_KNOWN, "Forest"),
        )
        val third = PerspectiveHistoryComposerV1.append(
            state = second,
            eventBatch = evidence(secondWitness, known = true).eventBatch,
            evidence = evidence(secondWitness, known = true),
            projection = projection("o1", HistoryCIdentityDisclosure.DEFINITION_KNOWN, "Forest"),
        )

        third.histories.getValue(perspective).entries.map { it.references.single().semanticAlias } shouldBe
            listOf("o0", "o0", "o1")
        third.histories.getValue(perspective).entries.first().references.single()
            .identityDisclosure shouldBe PerspectiveHistoryIdentityDisclosureV1.OPAQUE
        third.histories.getValue(perspective).entries[1].references.single()
            .identityDisclosure shouldBe PerspectiveHistoryIdentityDisclosureV1.DEFINITION_KNOWN
    }
})
