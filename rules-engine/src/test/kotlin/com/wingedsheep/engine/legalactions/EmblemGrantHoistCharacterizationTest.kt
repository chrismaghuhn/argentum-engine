package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.TurnManager
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.enumerators.ActivatedAbilityEnumerator
import com.wingedsheep.engine.mechanics.mana.CostCalculator
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.event.GrantedActivatedAbility
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.CreatedByComponent
import com.wingedsheep.engine.state.components.identity.EmblemActivatedAbilityComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.LinkedHashMap

/**
 * Semantic baseline cases for the emblem grant expression. These intentionally pass on the
 * unoptimized production implementation; the separate structural test is the optimization RED.
 */
class EmblemGrantSemanticCharacterizationTest : ScenarioTestBase() {

    private val printedHost = card("Emblem Hoist Printed Host") {
        manaCost = "{0}"
        typeLine = "Creature — Test"
        power = 2
        toughness = 2
        oracleText = "{0}: You gain 1 life."
        activatedAbility {
            cost = Costs.Free
            effect = Effects.GainLife(1)
        }
    }.let { definition ->
        val printed = definition.script.activatedAbilities.single().copy(
            id = AbilityId("printed_host_ability"),
        )
        definition.copy(script = definition.script.copy(activatedAbilities = listOf(printed)))
    }

    init {
        cardRegistry.register(printedHost)

        test("simple matching emblem grant produces one candidate") {
            val game = gameWithHosts("Grizzly Bears")
            val ability = ability("emblem_simple")
            installEmblem(game, "emblem-simple", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(ability))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(0, ability.id.value))
        }

        test("a rejecting filter produces no emblem candidate") {
            val game = gameWithHosts("Grizzly Bears")
            installEmblem(game, "emblem-reject", game.player1Id, GroupFilter(GameObjectFilter.Land), listOf(ability("emblem_reject")))

            assertEquivalent(game)
            productionEmblemCandidates(game) shouldBe emptyList()
        }

        test("one emblem selectively matches multiple hosts") {
            val game = gameWithHosts("Grizzly Bears", "Mountain", "Savannah Lions")
            val ability = ability("emblem_creature")
            installEmblem(game, "emblem-selective", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(ability))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(
                Candidate(0, ability.id.value),
                Candidate(2, ability.id.value),
            )
        }

        test("multiple emblems retain state entity order") {
            val game = gameWithHosts("Grizzly Bears")
            val first = ability("emblem_first")
            val second = ability("emblem_second")
            installEmblem(game, "emblem-first", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(first))
            installEmblem(game, "emblem-second", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(second))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(
                Candidate(0, first.id.value),
                Candidate(0, second.id.value),
            )
        }

        test("multiple abilities retain component list order") {
            val game = gameWithHosts("Grizzly Bears")
            val first = ability("emblem_ability_a")
            val second = ability("emblem_ability_b")
            installEmblem(game, "emblem-multi-ability", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(first, second))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(
                Candidate(0, first.id.value),
                Candidate(0, second.id.value),
            )
        }

        test("controller-relative filters use the emblem controller") {
            val game = gameWithHosts("Grizzly Bears")
            val ability = ability("emblem_controller")
            val filter = GroupFilter(GameObjectFilter.Creature.youControl())
            installEmblem(game, "emblem-opponent", game.player2Id, filter, listOf(ability))
            assertEquivalent(game)
            productionEmblemCandidates(game) shouldBe emptyList()

            installEmblem(game, "emblem-owner", game.player1Id, filter, listOf(ability))
            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(0, ability.id.value))
        }

        test("source-relative filters use the emblem entity as source") {
            val game = gameWithHosts("Grizzly Bears", "Savannah Lions")
            val emblemId = EntityId.of("emblem-source")
            val otherSource = EntityId.of("other-source")
            val hosts = game.state.getBattlefield(game.player1Id)
            game.state = game.state
                .updateEntity(hosts[0]) { it.with(TokenComponent).with(CreatedByComponent(emblemId)) }
                .updateEntity(hosts[1]) { it.with(TokenComponent).with(CreatedByComponent(otherSource)) }
            val ability = ability("emblem_source")
            installEmblem(game, emblemId, game.player1Id, GroupFilter(GameObjectFilter.Token.createdBySource()), listOf(ability))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(0, ability.id.value))
        }

        test("excludeSelf remains a host identity comparison") {
            val game = gameWithHosts("Grizzly Bears", "Savannah Lions")
            val hosts = game.state.getBattlefield(game.player1Id)
            val ability = ability("emblem_exclude_self")
            game.state = game.state.withEntity(
                hosts[0],
                checkNotNull(game.state.getEntity(hosts[0])).with(
                    EmblemActivatedAbilityComponent(
                        filter = GroupFilter(GameObjectFilter.Creature, excludeSelf = true),
                        abilities = listOf(ability),
                    ),
                ),
            )

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(1, ability.id.value))
        }

        test("printed temporary static and emblem abilities retain relative order") {
            val game = gameWithHosts(printedHost.name)
            val host = game.state.getBattlefield(game.player1Id).single()
            val temporary = ability("temporary_grant")
            val static = ability("static_grant")
            val emblem = ability("emblem_grant")
            game.state = game.state.copy(
                grantedActivatedAbilities = listOf(
                    GrantedActivatedAbility(host, temporary, Duration.Permanent),
                ),
                grantedStaticAbilities = listOf(
                    GrantedStaticAbility(
                        entityId = host,
                        ability = GrantActivatedAbility(static, GroupFilter.source()),
                        duration = Duration.Permanent,
                    ),
                ),
            )
            installEmblem(game, "emblem-order", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(emblem))

            assertEquivalent(game)
            allActivationCandidates(game).shouldContainExactly(
                Candidate(0, "printed_host_ability"),
                Candidate(0, temporary.id.value),
                Candidate(0, static.id.value),
                Candidate(0, emblem.id.value),
            )
        }

        test("emblem enumeration is read-only") {
            val game = gameWithHosts("Grizzly Bears")
            installEmblem(game, "emblem-read-only", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(ability("emblem_read_only")))
            val before = game.state

            assertEquivalent(game)
            assertEquivalent(game)

            game.state shouldBe before
            before.copy().entities.keys.toList() shouldBe before.entities.keys.toList()
            before.entities::class.java.name shouldBe "java.util.LinkedHashMap"
        }
    }

    private data class EmblemGrantDescriptor(
        val emblemEntityId: EntityId,
        val controllerId: EntityId,
        val filter: GroupFilter,
        val abilities: List<ActivatedAbility>,
    )

    private data class Candidate(val hostIndex: Int, val abilityId: String)

    private fun gameWithHosts(vararg names: String): ScenarioTestBase.TestGame {
        val builder = scenario().withPlayers()
        names.forEach { builder.withCardOnBattlefield(1, it) }
        return builder
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .build()
    }

    private fun ability(id: String) = ActivatedAbility(
        id = AbilityId(id),
        cost = Costs.Free,
        effect = Effects.GainLife(1),
    )

    private fun installEmblem(
        game: ScenarioTestBase.TestGame,
        id: String,
        controllerId: EntityId,
        filter: GroupFilter,
        abilities: List<ActivatedAbility>,
    ) = installEmblem(game, EntityId.of(id), controllerId, filter, abilities)

    private fun installEmblem(
        game: ScenarioTestBase.TestGame,
        id: EntityId,
        controllerId: EntityId,
        filter: GroupFilter,
        abilities: List<ActivatedAbility>,
    ) {
        game.state = game.state.withEntity(
            id,
            ComponentContainer.of(
                ControllerComponent(controllerId),
                EmblemActivatedAbilityComponent(filter, abilities),
            ),
        )
    }

    private fun collectDescriptors(state: GameState): List<EmblemGrantDescriptor> =
        state.entities.entries.mapNotNull { (id, container) ->
            val grant = container.get<EmblemActivatedAbilityComponent>() ?: return@mapNotNull null
            val controller = container.get<ControllerComponent>()?.playerId ?: return@mapNotNull null
            EmblemGrantDescriptor(id, controller, grant.filter, grant.abilities)
        }

    private fun referenceEmblemCandidates(state: GameState, playerId: EntityId): List<Candidate> {
        val descriptors = collectDescriptors(state)
        val projected = state.projectedState
        val evaluator = PredicateEvaluator()
        val hosts = projected.getBattlefieldControlledBy(playerId)
        return hosts.flatMapIndexed { hostIndex, hostId ->
            descriptors.flatMap { descriptor ->
                val matches = evaluator.matches(
                    state,
                    projected,
                    hostId,
                    descriptor.filter.baseFilter,
                    PredicateContext(
                        controllerId = descriptor.controllerId,
                        sourceId = descriptor.emblemEntityId,
                    ),
                ) && (!descriptor.filter.excludeSelf || hostId != descriptor.emblemEntityId)
                if (matches) descriptor.abilities.map { Candidate(hostIndex, it.id.value) } else emptyList()
            }
        }
    }

    private fun productionEmblemCandidates(game: ScenarioTestBase.TestGame): List<Candidate> {
        val descriptors = collectDescriptors(game.state)
        val abilityIds = descriptors.flatMap { it.abilities }.map { it.id.value }.toSet()
        val hosts = game.state.projectedState.getBattlefieldControlledBy(game.player1Id)
        return LegalActionEnumerator.create(cardRegistry)
            .enumerate(game.state, game.player1Id, EnumerationMode.ACTIONS_ONLY)
            .mapNotNull { legalAction ->
                val action = legalAction.action as? ActivateAbility ?: return@mapNotNull null
                if (action.sourceId !in hosts || action.abilityId.value !in abilityIds) return@mapNotNull null
                Candidate(hosts.indexOf(action.sourceId), action.abilityId.value)
            }
    }

    private fun allActivationCandidates(game: ScenarioTestBase.TestGame): List<Candidate> {
        val hosts = game.state.projectedState.getBattlefieldControlledBy(game.player1Id)
        return LegalActionEnumerator.create(cardRegistry)
            .enumerate(game.state, game.player1Id, EnumerationMode.ACTIONS_ONLY)
            .mapNotNull { legalAction ->
                val action = legalAction.action as? ActivateAbility ?: return@mapNotNull null
                val index = hosts.indexOf(action.sourceId)
                if (index < 0) null else Candidate(index, action.abilityId.value)
            }
    }

    private fun assertEquivalent(game: ScenarioTestBase.TestGame) {
        val reference = referenceEmblemCandidates(game.state, game.player1Id)
        val production = productionEmblemCandidates(game)
        production shouldBe reference
        fingerprint(production) shouldBe fingerprint(reference)
    }

    private fun fingerprint(candidates: List<Candidate>): String =
        candidates.joinToString("|") { "host=${it.hostIndex};ability=${it.abilityId}" }
}

/**
 * Structural RED for the authorized optimization. The counted map is used only to isolate the
 * current emblem expression from unrelated state preparation and legal-action work.
 */
class EmblemGrantHoistStructuralTest : ScenarioTestBase() {

    init {
        test("hoisted emblem discovery scans the entity map once per enumeration") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Savannah Lions")
                .withCardOnBattlefield(1, "Elvish Mystic")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()
            val descriptor = EmblemActivatedAbilityComponent(
                filter = GroupFilter(GameObjectFilter.Creature),
                abilities = listOf(ActivatedAbility(AbilityId("structural_emblem"), Costs.Free, Effects.GainLife(1))),
            )
            val backing = LinkedHashMap(game.state.entities)
            backing[EntityId.of("structural-emblem")] = ComponentContainer.of(
                ControllerComponent(game.player1Id),
                descriptor,
            )
            val counted = CountingEntityMap(backing)
            val state = game.state.copy(entities = counted)
            val context = EnumerationContext(
                state = state,
                playerId = game.player1Id,
                cardRegistry = cardRegistry,
                manaSolver = ManaSolver(cardRegistry),
                costCalculator = CostCalculator(cardRegistry),
                predicateEvaluator = PredicateEvaluator(),
                conditionEvaluator = ConditionEvaluator(),
                turnManager = TurnManager(cardRegistry),
                mode = EnumerationMode.ACTIONS_ONLY,
            )

            // State projection and context preparation are outside the measured expression.
            context.projected
            context.battlefieldPermanents
            context.availableManaSources
            counted.resetCounters()

            ActivatedAbilityEnumerator().enumerate(context)

            counted.scanCount shouldBe 1
        }
    }

    private class CountingEntityMap(
        private val backing: LinkedHashMap<EntityId, ComponentContainer>,
    ) : Map<EntityId, ComponentContainer> {
        var scanCount: Int = 0
            private set
        var entriesVisited: Int = 0
            private set

        override val size: Int get() = backing.size

        override val entries: Set<Map.Entry<EntityId, ComponentContainer>> =
            object : kotlin.collections.AbstractSet<Map.Entry<EntityId, ComponentContainer>>() {
                override val size: Int get() = backing.size

                override fun iterator(): Iterator<Map.Entry<EntityId, ComponentContainer>> {
                    scanCount++
                    val iterator = backing.entries.iterator()
                    return object : Iterator<Map.Entry<EntityId, ComponentContainer>> {
                        override fun hasNext(): Boolean = iterator.hasNext()

                        override fun next(): Map.Entry<EntityId, ComponentContainer> {
                            entriesVisited++
                            return iterator.next()
                        }
                    }
                }
            }

        override fun get(key: EntityId): ComponentContainer? = backing[key]

        override fun containsKey(key: EntityId): Boolean = backing.containsKey(key)

        override fun containsValue(value: ComponentContainer): Boolean = backing.containsValue(value)

        override fun isEmpty(): Boolean = backing.isEmpty()

        fun resetCounters() {
            scanCount = 0
            entriesVisited = 0
        }

        override val keys: Set<EntityId> get() = backing.keys

        override val values: Collection<ComponentContainer> get() = backing.values
    }
}
