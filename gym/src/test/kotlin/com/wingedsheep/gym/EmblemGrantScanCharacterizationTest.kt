package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.handlers.PredicateContext
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.event.GrantedActivatedAbility
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
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
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Test-only semantic comparison for a possible per-enumeration emblem descriptor collection.
 *
 * Production [com.wingedsheep.engine.legalactions.enumerators.ActivatedAbilityEnumerator] remains
 * authoritative. The reference below copies only the current emblem discovery/evaluation
 * expression, including its controller/source context and ordering; it does not enumerate any
 * other ability family.
 */
class EmblemGrantScanCharacterizationTest : ScenarioTestBase() {

    private val printedHost = card("Emblem Characterization Printed Host") {
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

        test("simple matching emblem grant is equivalent") {
            val game = gameWithHosts("Grizzly Bears")
            val granted = ability("emblem_simple")
            installEmblem(game, "emblem-simple", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(granted))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(0, granted.id.value))
        }

        test("a non-matching emblem filter publishes no candidate") {
            val game = gameWithHosts("Grizzly Bears")
            installEmblem(game, "emblem-reject", game.player1Id, GroupFilter(GameObjectFilter.Land), listOf(ability("emblem_reject")))

            assertEquivalent(game)
            productionEmblemCandidates(game) shouldBe emptyList()
        }

        test("one emblem selects only the matching hosts in host order") {
            val game = gameWithHosts("Grizzly Bears", "Mountain", "Savannah Lions")
            val granted = ability("emblem_creature")
            installEmblem(game, "emblem-selective", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(granted))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(
                Candidate(0, granted.id.value),
                Candidate(2, granted.id.value),
            )
        }

        test("multiple emblems retain state entity order") {
            val game = gameWithHosts("Grizzly Bears")
            val first = ability("emblem_first")
            val second = ability("emblem_second")
            installEmblem(game, "emblem-first", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(first))
            installEmblem(game, "emblem-second", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(second))

            val reference = referenceEmblemProjection(game.state, game.player1Id)
            reference.descriptors.map { it.emblemEntityId.value } shouldBe listOf("emblem-first", "emblem-second")
            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(
                Candidate(0, first.id.value),
                Candidate(0, second.id.value),
            )
        }

        test("abilities inside one emblem grant retain component list order") {
            val game = gameWithHosts("Grizzly Bears")
            val first = ability("emblem_ability_a")
            val second = ability("emblem_ability_b")
            installEmblem(
                game,
                "emblem-multi-ability",
                game.player1Id,
                GroupFilter(GameObjectFilter.Creature),
                listOf(first, second),
            )

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(
                Candidate(0, first.id.value),
                Candidate(0, second.id.value),
            )
        }

        test("emblem controller is the controller predicate authority") {
            val game = gameWithHosts("Grizzly Bears")
            val granted = ability("emblem_controller")
            installEmblem(
                game,
                "emblem-opponent-controller",
                game.player2Id,
                GroupFilter(GameObjectFilter.Creature.youControl()),
                listOf(granted),
            )

            assertEquivalent(game)
            productionEmblemCandidates(game) shouldBe emptyList()

            installEmblem(
                game,
                "emblem-own-controller",
                game.player1Id,
                GroupFilter(GameObjectFilter.Creature.youControl()),
                listOf(granted),
            )
            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(0, granted.id.value))
        }

        test("emblem source is retained for source-sensitive predicates") {
            val game = gameWithHosts("Grizzly Bears", "Savannah Lions")
            val emblemId = EntityId.of("emblem-source")
            val otherSource = EntityId.of("other-source")
            val hosts = game.state.getBattlefield(game.player1Id)
            game.state = game.state
                .updateEntity(hosts[0]) { it.with(TokenComponent).with(CreatedByComponent(emblemId)) }
                .updateEntity(hosts[1]) { it.with(TokenComponent).with(CreatedByComponent(otherSource)) }
            val granted = ability("emblem_source")
            installEmblem(game, emblemId.value, game.player1Id, GroupFilter(GameObjectFilter.Token.createdBySource()), listOf(granted))

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(0, granted.id.value))
        }

        test("excludeSelf follows the current generic comparison") {
            val game = gameWithHosts("Grizzly Bears", "Savannah Lions")
            val hostIds = game.state.getBattlefield(game.player1Id)
            val granted = ability("emblem_exclude_self")
            game.state = game.state.withEntity(
                hostIds[0],
                checkNotNull(game.state.getEntity(hostIds[0])).with(
                    EmblemActivatedAbilityComponent(
                        filter = GroupFilter(GameObjectFilter.Creature, excludeSelf = true),
                        abilities = listOf(granted),
                    ),
                ),
            )

            assertEquivalent(game)
            productionEmblemCandidates(game).shouldContainExactly(Candidate(1, granted.id.value))
        }

        test("printed, temporary, static, and emblem abilities retain their relative order") {
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
            installEmblem(game, "emblem-relative-order", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(emblem))

            assertEquivalent(game)
            allActivationCandidates(game).shouldContainExactly(
                Candidate(0, "printed_host_ability"),
                Candidate(0, temporary.id.value),
                Candidate(0, static.id.value),
                Candidate(0, emblem.id.value),
            )
        }

        test("enumeration is read-only and repeated state traversal is stable") {
            val game = gameWithHosts("Grizzly Bears")
            val first = ability("emblem_stable")
            val second = ability("emblem_stable_second")
            installEmblem(game, "emblem-stable-a", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(first))
            installEmblem(game, "emblem-stable-b", game.player1Id, GroupFilter(GameObjectFilter.Creature), listOf(second))
            val stateBefore = game.state
            val firstOrder = stateBefore.entities.keys.toList()

            assertEquivalent(game)
            assertEquivalent(game)

            game.state shouldBe stateBefore
            stateBefore.copy().entities.keys.toList() shouldBe firstOrder
            stateBefore.entities::class.java.name shouldBe "java.util.LinkedHashMap"
        }
    }

    private data class EmblemGrantDescriptor(
        val emblemEntityId: EntityId,
        val controllerId: EntityId,
        val filter: GroupFilter,
        val abilities: List<ActivatedAbility>,
    )

    private data class Candidate(
        val hostIndex: Int,
        val abilityId: String,
    )

    private data class ReferenceProjection(
        val descriptors: List<EmblemGrantDescriptor>,
        val candidates: List<Candidate>,
        val currentEntityScanCount: Int,
        val currentEntityEntriesVisited: Int,
        val descriptorCollectionScanCount: Int,
        val descriptorEvaluationCount: Int,
    )

    private fun gameWithHosts(vararg cardNames: String): ScenarioTestBase.TestGame {
        val builder = scenario().withPlayers()
        cardNames.forEach { builder.withCardOnBattlefield(1, it) }
        return builder
            .withActivePlayer(1)
            .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            .build()
    }

    private fun ability(id: String): ActivatedAbility = ActivatedAbility(
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
    ) {
        installEmblem(game, EntityId.of(id), controllerId, filter, abilities)
    }

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

    private fun collectEmblemGrantDescriptors(state: GameState): List<EmblemGrantDescriptor> =
        state.entities.entries.mapNotNull { (emblemId, container) ->
            val grant = container.get<EmblemActivatedAbilityComponent>() ?: return@mapNotNull null
            val controllerId = container.get<ControllerComponent>()?.playerId ?: return@mapNotNull null
            EmblemGrantDescriptor(emblemId, controllerId, grant.filter, grant.abilities)
        }

    /**
     * The reference intentionally mirrors the production expression, not an imagined normalized
     * filter model. In particular, [GroupFilter.scope] is not interpreted here because production
     * passes only `baseFilter` and applies `excludeSelf` explicitly at this seam.
     */
    private fun referenceEmblemProjection(state: GameState, playerId: EntityId): ReferenceProjection {
        val descriptors = collectEmblemGrantDescriptors(state)
        val projected = state.projectedState
        val evaluator = PredicateEvaluator()
        val hosts = projected.getBattlefieldControlledBy(playerId)
        val candidates = hosts.flatMapIndexed { hostIndex, hostId ->
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
        return ReferenceProjection(
            descriptors = descriptors,
            candidates = candidates,
            currentEntityScanCount = hosts.size,
            currentEntityEntriesVisited = hosts.size * state.entities.size,
            descriptorCollectionScanCount = 1,
            descriptorEvaluationCount = hosts.size * descriptors.size,
        )
    }

    private fun productionEmblemCandidates(game: ScenarioTestBase.TestGame): List<Candidate> {
        val descriptors = collectEmblemGrantDescriptors(game.state)
        val emblemAbilityIds = descriptors.flatMap { descriptor -> descriptor.abilities.map { it.id.value } }.toSet()
        val hosts = game.state.projectedState.getBattlefieldControlledBy(game.player1Id)
        return LegalActionEnumerator.create(cardRegistry)
            .enumerate(game.state, game.player1Id, EnumerationMode.ACTIONS_ONLY)
            .mapNotNull { legalAction ->
                val action = legalAction.action as? ActivateAbility ?: return@mapNotNull null
                if (action.sourceId !in hosts || action.abilityId.value !in emblemAbilityIds) return@mapNotNull null
                Candidate(hosts.indexOf(action.sourceId), action.abilityId.value)
            }
    }

    private fun allActivationCandidates(game: ScenarioTestBase.TestGame): List<Candidate> {
        val hosts = game.state.projectedState.getBattlefieldControlledBy(game.player1Id)
        return LegalActionEnumerator.create(cardRegistry)
            .enumerate(game.state, game.player1Id, EnumerationMode.ACTIONS_ONLY)
            .mapNotNull { legalAction ->
                val action = legalAction.action as? ActivateAbility ?: return@mapNotNull null
                val hostIndex = hosts.indexOf(action.sourceId)
                if (hostIndex < 0) null else Candidate(hostIndex, action.abilityId.value)
            }
    }

    private fun assertEquivalent(game: ScenarioTestBase.TestGame) {
        val reference = referenceEmblemProjection(game.state, game.player1Id)
        val production = productionEmblemCandidates(game)
        production shouldBe reference.candidates
        fingerprint(production) shouldBe fingerprint(reference.candidates)
        reference.currentEntityScanCount shouldBe game.state.projectedState
            .getBattlefieldControlledBy(game.player1Id).size
        reference.currentEntityEntriesVisited shouldBe reference.currentEntityScanCount * game.state.entities.size
        reference.descriptorCollectionScanCount shouldBe 1
        reference.descriptorEvaluationCount shouldBe reference.currentEntityScanCount * reference.descriptors.size
    }

    private fun fingerprint(candidates: List<Candidate>): String =
        candidates.joinToString("|") { "host=${it.hostIndex};ability=${it.abilityId}" }
}
