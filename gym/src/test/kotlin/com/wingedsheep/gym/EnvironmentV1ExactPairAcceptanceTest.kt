package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardDefinitionMissingException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.EnvId
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.StepRequest
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeMap
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText

/**
 * Durable exact-pair Environment V1 setup and corpus gate.
 *
 * This file is intentionally test-only. It drives the trusted public service surface and never
 * passes the environment or an internal registry to [DeterministicExternalPolicy].
 */
class EnvironmentV1ExactPairAcceptanceTest : FunSpec({

    test("the locked Akiri and Chevill files resolve exactly 146 unique cards") {
        val akiri = readLockedDeck("akiri-v0.1.txt")
        val chevill = readLockedDeck("chevill-v0.1.txt")
        val uniqueCards = (akiri.cards + chevill.cards).distinct()
        val registry = exactPairRegistry()

        sha256(lockedDeckPath("akiri-v0.1.txt")) shouldBe AKIRI_SHA256
        sha256(lockedDeckPath("chevill-v0.1.txt")) shouldBe CHEVILL_SHA256
        akiri.cards.size shouldBe 100
        chevill.cards.size shouldBe 100
        uniqueCards.size shouldBe 146
        akiri.commander shouldBe "Akiri, Fearless Voyager"
        chevill.commander shouldBe "Chevill, Bane of Monsters"
        uniqueCards.filterNot(registry::hasCard).shouldBeEmpty()
    }

    test("the external policy has no engine-state or diagnostic dependency") {
        val source = repositoryRoot()
            .resolve("gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt")
            .readText()
        listOf(
            "GameState",
            "CardRegistry",
            "ActionRegistry",
            "EpisodeDiagnostics",
            "GameEnvironment",
        ).forEach { forbidden ->
            check(forbidden !in source) {
                "Observation-only acceptance policy contains forbidden symbol: $forbidden"
            }
        }
    }

    test("runs the exact 72-episode trusted corpus with first-gap stop semantics") {
        val evidence = runExactPairCorpus()
        println(evidence.render())

        evidence.firstFailure?.let { failure ->
            error("Environment V1 corpus stopped at first real finding: $failure")
        }
        evidence.episodesStarted shouldBe 72
        evidence.terminalEpisodes + evidence.truncatedEpisodes shouldBe 72
        evidence.totalExternalTransitions shouldBe evidence.episodeTransitions.sum()
    }
}) {
    private companion object {
        const val AKIRI_SHA256 =
            "E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04"
        const val CHEVILL_SHA256 =
            "0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474"
        const val MAX_STEPS = 2_000

        fun runExactPairCorpus(): CorpusEvidence {
            val evidence = CorpusEvidence()
            val policy = DeterministicExternalPolicy()
            val service = MultiEnvService(exactPairRegistry())
            try {
                for (episode in corpusCases()) {
                    if (evidence.firstFailure != null) break
                    val result = runEpisode(service, policy, episode)
                    evidence.record(result)
                }
            } finally {
                service.dispose(service.listEnvs())
            }
            return evidence
        }

        fun runEpisode(
            service: MultiEnvService,
            policy: DeterministicExternalPolicy,
            episode: EpisodeConfig,
        ): EpisodeResult {
            val policyState = DeterministicPolicyState(policySeed(episode))
            var state = policyState
            var envId: EnvId? = null
            var observation: TrainingObservation? = null
            var lastFamily = "RESET"
            var lastActionKind = "RESET"
            var transitions = 0
            val actionKinds = TreeMap<String, Int>()
            val decisionFamilies = TreeMap<String, Int>()
            var commanderZoneDecisions = 0
            var paymentDecisions = 0
            var searchDecisions = 0
            var combatDecisions = 0

            fun currentFailure(
                classification: String,
                code: String,
                reason: String,
            ): AcceptanceFailure = AcceptanceFailure(
                classification = classification,
                code = code,
                reason = reason,
                seed = episode.seed,
                policySeed = policyState.policySeed,
                roster = episode.rosterLabel,
                startingPlayerIndex = episode.startingPlayerIndex,
                step = transitions,
                actor = observation?.agentToAct?.value,
                stateDigest = observation?.stateDigest,
                decisionFamily = lastFamily,
                actionKind = lastActionKind,
            )

            fun observe(result: ObservationResult): TrainingObservation {
                check(result.diagnostics.isEmpty()) {
                    "The public observation result carried internal diagnostics"
                }
                val game = result.observation as? TrainingObservation
                    ?: error("Exact-pair corpus requires a TrainingObservation")
                observation = game
                return game
            }

            fun diagnosticFailure(): AcceptanceFailure? {
                val id = envId ?: return null
                val diagnostics = service.diagnostics(id)
                val signal = diagnostics.events.firstOrNull() ?: return null
                val classification = when (signal.kind) {
                    DiagnosticKind.UNSUPPORTED_CARD -> "A9_UNSUPPORTED_CARD"
                    DiagnosticKind.UNSUPPORTED_DECISION -> "A9_UNSUPPORTED_DECISION"
                    DiagnosticKind.UNSUPPORTED_RULE_OR_MECHANIC ->
                        "A9_UNSUPPORTED_RULE_OR_MECHANIC"
                    DiagnosticKind.NATIVE_POLICY_FALLBACK -> "A5_NATIVE_POLICY_FALLBACK"
                }
                return currentFailure(
                    classification = classification,
                    code = signal.semanticCode,
                    reason = "Authoritative trusted-episode diagnostic was recorded",
                )
            }

            fun assertDiagnosticsZero(): AcceptanceFailure? {
                val id = envId ?: return null
                val diagnostics = service.diagnostics(id)
                if (diagnostics.unsupportedCardCount == 0 &&
                    diagnostics.unsupportedDecisionCount == 0 &&
                    diagnostics.unsupportedRuleCount == 0 &&
                    diagnostics.nativePolicyFallbackCount == 0
                ) {
                    return null
                }
                return diagnosticFailure()
                    ?: currentFailure(
                        classification = "A9_UNSUPPORTED_RULE_OR_MECHANIC",
                        code = "UNKNOWN_DIAGNOSTIC",
                        reason = "A diagnostic counter became non-zero without a typed event",
                    )
            }

            fun countPublicBoundary(game: TrainingObservation) {
                val pending = game.pendingDecision
                val family = pending?.kind?.name ?: "PRIORITY"
                lastFamily = family
                decisionFamilies[family] = (decisionFamilies[family] ?: 0) + 1
                val publicText = listOf(
                    pending?.prompt,
                    pending?.sourceName,
                    pending?.effectHint,
                ).filterNotNull().joinToString(" ")
                if (publicText.contains("commander", ignoreCase = true) ||
                    family.contains("COMMANDER", ignoreCase = true)
                ) {
                    commanderZoneDecisions++
                }
                if (family == PendingDecisionKind.SELECT_MANA_SOURCES.name ||
                    game.legalActions.any { it.manaCost != null }
                ) {
                    paymentDecisions++
                }
                if (family == PendingDecisionKind.SEARCH_LIBRARY.name ||
                    family.contains("SEARCH", ignoreCase = true)
                ) {
                    searchDecisions++
                }
                if (family == PendingDecisionKind.COMBAT_RESOLUTION.name ||
                    family.contains("COMBAT", ignoreCase = true) ||
                    game.legalActions.any {
                        it.kind.contains("Attack", ignoreCase = true) ||
                            it.kind.contains("Block", ignoreCase = true)
                    }
                ) {
                    combatDecisions++
                }
            }

            return try {
                val created = service.create(episode.envConfig())
                envId = created.envId
                var game = observe(created.observation)
                assertDiagnosticsZero()?.let { failure ->
                    return EpisodeResult(
                        episode = episode,
                        transitions = transitions,
                        terminal = game.terminated,
                        truncated = game.truncated,
                        winner = game.winnerId,
                        actionKinds = actionKinds,
                        decisionFamilies = decisionFamilies,
                        commanderZoneDecisions = commanderZoneDecisions,
                        paymentDecisions = paymentDecisions,
                        searchDecisions = searchDecisions,
                        combatDecisions = combatDecisions,
                        failure = failure,
                    )
                }

                while (!game.terminated && !game.truncated) {
                    if (transitions >= MAX_STEPS) {
                        val failure = currentFailure(
                            classification = "A3_GYM_INTEGRATION_GAP",
                            code = "MAX_STEPS_NOT_REPORTED",
                            reason = "The environment exceeded its configured external horizon",
                        )
                        return EpisodeResult(
                            episode = episode,
                            transitions = transitions,
                            terminal = false,
                            truncated = false,
                            winner = null,
                            actionKinds = actionKinds,
                            decisionFamilies = decisionFamilies,
                            commanderZoneDecisions = commanderZoneDecisions,
                            paymentDecisions = paymentDecisions,
                            searchDecisions = searchDecisions,
                            combatDecisions = combatDecisions,
                            failure = failure,
                        )
                    }
                    check(game.agentToAct != null) {
                        "Nonterminal observation did not publish agentToAct"
                    }
                    if (game.pendingDecision == null && game.legalActions.isEmpty()) {
                        val failure = currentFailure(
                            classification = "A5_DECISION_GAP",
                            code = "EMPTY_EXTERNAL_ACTION_DOMAIN",
                            reason = "Nonterminal priority state published no legal actions",
                        )
                        return EpisodeResult(
                            episode = episode,
                            transitions = transitions,
                            terminal = false,
                            truncated = false,
                            winner = null,
                            actionKinds = actionKinds,
                            decisionFamilies = decisionFamilies,
                            commanderZoneDecisions = commanderZoneDecisions,
                            paymentDecisions = paymentDecisions,
                            searchDecisions = searchDecisions,
                            combatDecisions = combatDecisions,
                            failure = failure,
                        )
                    }

                    countPublicBoundary(game)
                    val choice = policy.choose(game, state)
                    state = state.afterChoice()
                    when (choice) {
                        is SemanticChoice.Gap -> {
                            lastFamily = choice.family
                            val failure = currentFailure(
                                classification = choice.code,
                                code = choice.code,
                                reason = choice.reason,
                            )
                            return EpisodeResult(
                                episode = episode,
                                transitions = transitions,
                                terminal = false,
                                truncated = false,
                                winner = null,
                                actionKinds = actionKinds,
                                decisionFamilies = decisionFamilies,
                                commanderZoneDecisions = commanderZoneDecisions,
                                paymentDecisions = paymentDecisions,
                                searchDecisions = searchDecisions,
                                combatDecisions = combatDecisions,
                                failure = failure,
                            )
                        }

                        is SemanticChoice.Action -> {
                            lastActionKind = choice.kind
                            actionKinds[choice.kind] = (actionKinds[choice.kind] ?: 0) + 1
                            val result = service.step(
                                StepRequest(
                                    envId = envId!!,
                                    actionId = choice.actionId,
                                    action = choice.payload,
                                )
                            )
                            transitions++
                            game = observe(result)
                        }

                        is SemanticChoice.Structured -> {
                            val pending = game.pendingDecision
                                ?: error("Structured choice without a pending decision")
                            val decisionId = pending.decisionId
                                ?: error("Actor-facing structured decision has no decisionId")
                            val response = toDecisionResponse(decisionId, choice.selection)
                            lastActionKind = "DECISION"
                            val result = service.submitDecision(
                                envId = envId!!,
                                response = response,
                                actorId = game.agentToAct,
                            )
                            transitions++
                            game = observe(result)
                        }
                    }

                    assertDiagnosticsZero()?.let { failure ->
                        return EpisodeResult(
                            episode = episode,
                            transitions = transitions,
                            terminal = game.terminated,
                            truncated = game.truncated,
                            winner = game.winnerId,
                            actionKinds = actionKinds,
                            decisionFamilies = decisionFamilies,
                            commanderZoneDecisions = commanderZoneDecisions,
                            paymentDecisions = paymentDecisions,
                            searchDecisions = searchDecisions,
                            combatDecisions = combatDecisions,
                            failure = failure,
                        )
                    }
                    if (game.terminated || game.truncated) break
                }

                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = game.terminated,
                    truncated = game.truncated,
                    winner = game.winnerId,
                    actionKinds = actionKinds,
                    decisionFamilies = decisionFamilies,
                    commanderZoneDecisions = commanderZoneDecisions,
                    paymentDecisions = paymentDecisions,
                    searchDecisions = searchDecisions,
                    combatDecisions = combatDecisions,
                    failure = null,
                )
            } catch (failure: CardDefinitionMissingException) {
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = currentFailure(
                        classification = "A9_UNSUPPORTED_CARD",
                        code = failure.code,
                        reason = "Locked-card setup failed at the registry boundary",
                    ),
                )
            } catch (failure: UnsupportedPathFailure) {
                val signal = failure.diagnostics.firstOrNull()
                val diagnostic = diagnosticFailure()
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = diagnostic ?: currentFailure(
                        classification = "A9_UNSUPPORTED_RULE_OR_MECHANIC",
                        code = signal?.semanticCode ?: "UNSUPPORTED_PATH_FAILURE",
                        reason = "Trusted execution raised an unsupported-path failure",
                    ),
                )
            } catch (failure: IllegalArgumentException) {
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = currentFailure(
                        classification = "A5_CANDIDATE_CONTRACT_GAP",
                        code = "PUBLIC_CHOICE_REJECTED",
                        reason = "A choice generated from the published domain was rejected",
                    ),
                )
            } catch (failure: IllegalStateException) {
                EpisodeResult(
                    episode = episode,
                    transitions = transitions,
                    terminal = false,
                    truncated = false,
                    winner = null,
                    failure = currentFailure(
                        classification = "A3_GYM_INTEGRATION_GAP",
                        code = "TRUSTED_STEP_REJECTED",
                        reason = "The trusted service rejected an otherwise typed corpus step",
                    ),
                )
            } finally {
                envId?.let { service.dispose(listOf(it)) }
            }
        }

        fun toDecisionResponse(
            decisionId: String,
            selection: SemanticDecision,
        ): DecisionResponse = when (selection) {
            is SemanticDecision.Targets -> TargetsResponse(decisionId, selection.selected)
            is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, selection.selected)
            is SemanticDecision.Modes -> ModesChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Color -> ColorChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Number -> NumberChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Distribution ->
                DistributionResponse(decisionId, selection.selected)
            is SemanticDecision.Ordered -> OrderedResponse(decisionId, selection.selected)
            is SemanticDecision.Piles -> PilesSplitResponse(decisionId, selection.selected)
            is SemanticDecision.Option -> OptionChosenResponse(decisionId, selection.selected)
            is SemanticDecision.Replacement ->
                ReplacementChosenResponse(decisionId, selection.from, selection.to)
            is SemanticDecision.Budget -> BudgetModalResponse(decisionId, selection.selected)
            is SemanticDecision.Damage -> CombatResolutionResponse(
                decisionId = decisionId,
                edges = selection.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
            )
            is SemanticDecision.Mana -> ManaSourcesSelectedResponse(
                decisionId = decisionId,
                selectedSources = selection.selectedSources,
                autoPay = false,
                waterbendPermanents = selection.waterbendPermanents.toSet(),
                declined = selection.declined,
            )
        }

        fun corpusCases(): List<EpisodeConfig> {
            val primary = (0L..31L).flatMap { seed ->
                listOf(0, 1).map { startingPlayerIndex ->
                    EpisodeConfig(
                        seed = seed,
                        startingPlayerIndex = startingPlayerIndex,
                        seat0 = "Akiri",
                        seat1 = "Chevill",
                        rosterLabel = "Akiri-vs-Chevill",
                    )
                }
            }
            val rosterSwap = (0L..3L).flatMap { seed ->
                listOf(0, 1).map { startingPlayerIndex ->
                    EpisodeConfig(
                        seed = seed,
                        startingPlayerIndex = startingPlayerIndex,
                        seat0 = "Chevill",
                        seat1 = "Akiri",
                        rosterLabel = "Chevill-vs-Akiri",
                    )
                }
            }
            return primary + rosterSwap
        }

        fun policySeed(episode: EpisodeConfig): Long {
            val roster = if (episode.seat0 == "Akiri") 0x41L else 0x43L
            return episode.seed * 1_000_003L +
                episode.startingPlayerIndex * 97_409L +
                roster * 65_537L
        }

        fun EpisodeConfig.envConfig(): EnvConfig {
            val akiri = readLockedDeck("akiri-v0.1.txt")
            val chevill = readLockedDeck("chevill-v0.1.txt")
            val decks = mapOf(
                "Akiri" to akiri,
                "Chevill" to chevill,
            )
            fun player(name: String): PlayerSpec {
                val deck = decks.getValue(name)
                return PlayerSpec(
                    name = name,
                    deck = DeckSpec.Explicit(
                        deck.cards.drop(1).groupingBy { it }.eachCount(),
                    ),
                    startingLife = 40,
                    commanderCardName = deck.commander,
                )
            }
            return EnvConfig(
                players = listOf(player(seat0), player(seat1)),
                format = Format.Commander(),
                startingHandSize = 7,
                skipMulligans = true,
                useHandSmoother = false,
                startingPlayerIndex = startingPlayerIndex,
                seed = seed,
                maxSteps = MAX_STEPS,
                perspectivePlayerIndex = 0,
            )
        }

        fun exactPairRegistry(): CardRegistry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }

        fun readLockedDeck(fileName: String): LockedDeck {
            val path = lockedDeckPath(fileName)
            val cards = path.readLines()
                .filter { it.matches(Regex("^\\d{3}\\t.*")) }
                .map { it.substringAfterLast('\t') }
            return LockedDeck(
                commander = cards.first(),
                cards = cards,
            )
        }

        fun lockedDeckPath(fileName: String): Path =
            repositoryRoot().resolve("docs/ml/curriculum").resolve(fileName)

        fun repositoryRoot(): Path {
            val workingDirectory = Path.of(System.getProperty("user.dir"))
            return generateSequence(workingDirectory) { it.parent }
                .first { it.resolve("docs/ml/curriculum").toFile().isDirectory }
        }

        fun sha256(path: Path): String =
            MessageDigest.getInstance("SHA-256")
                .digest(path.readBytes())
                .joinToString("") { byte -> "%02X".format(byte) }
    }
}

private data class LockedDeck(
    val commander: String,
    val cards: List<String>,
)

private data class EpisodeConfig(
    val seed: Long,
    val startingPlayerIndex: Int,
    val seat0: String,
    val seat1: String,
    val rosterLabel: String,
)

private data class AcceptanceFailure(
    val classification: String,
    val code: String,
    val reason: String,
    val seed: Long,
    val policySeed: Long,
    val roster: String,
    val startingPlayerIndex: Int,
    val step: Int,
    val actor: String?,
    val stateDigest: String?,
    val decisionFamily: String,
    val actionKind: String,
) {
    override fun toString(): String =
        listOf(
            "classification=" + classification,
            "code=" + code,
            "seed=" + seed,
            "policySeed=" + policySeed,
            "roster=" + roster,
            "startingPlayerIndex=" + startingPlayerIndex,
            "step=" + step,
            "actor=" + (actor ?: "null"),
            "stateDigest=" + (stateDigest ?: "null"),
            "decisionFamily=" + decisionFamily,
            "actionKind=" + actionKind,
            "reason=" + reason,
        ).joinToString(" ")
}

private data class EpisodeResult(
    val episode: EpisodeConfig,
    val transitions: Int,
    val terminal: Boolean,
    val truncated: Boolean,
    val winner: EntityId?,
    val actionKinds: Map<String, Int> = emptyMap(),
    val decisionFamilies: Map<String, Int> = emptyMap(),
    val commanderZoneDecisions: Int = 0,
    val paymentDecisions: Int = 0,
    val searchDecisions: Int = 0,
    val combatDecisions: Int = 0,
    val failure: AcceptanceFailure?,
)

private class CorpusEvidence {
    var episodesStarted: Int = 0
        private set
    var terminalEpisodes: Int = 0
        private set
    var truncatedEpisodes: Int = 0
        private set
    var totalExternalTransitions: Int = 0
        private set
    var firstFailure: AcceptanceFailure? = null
        private set
    val episodeTransitions = mutableListOf<Int>()
    private val actionKinds = TreeMap<String, Int>()
    private val decisionFamilies = TreeMap<String, Int>()
    private val diagnosticKinds = TreeMap<String, Int>()
    private val diagnosticCodes = TreeMap<String, Int>()
    var commanderZoneDecisions: Int = 0
        private set
    var paymentDecisions: Int = 0
        private set
    var searchDecisions: Int = 0
        private set
    var combatDecisions: Int = 0
        private set

    fun record(result: EpisodeResult) {
        episodesStarted++
        totalExternalTransitions += result.transitions
        episodeTransitions += result.transitions
        if (result.terminal) terminalEpisodes++
        if (result.truncated) truncatedEpisodes++
        commanderZoneDecisions += result.commanderZoneDecisions
        paymentDecisions += result.paymentDecisions
        searchDecisions += result.searchDecisions
        combatDecisions += result.combatDecisions
        result.actionKinds.forEach { (key, value) -> actionKinds[key] = (actionKinds[key] ?: 0) + value }
        result.decisionFamilies.forEach { (key, value) ->
            decisionFamilies[key] = (decisionFamilies[key] ?: 0) + value
        }
        result.failure?.let { failure ->
            if (firstFailure == null) firstFailure = failure
            diagnosticCodes[failure.code] = (diagnosticCodes[failure.code] ?: 0) + 1
        }
    }

    fun render(): String = buildString {
        appendLine("ENVIRONMENT_V1_CORPUS")
        appendLine("targetEpisodes=72")
        appendLine("episodesStarted=" + episodesStarted)
        appendLine("terminalEpisodes=" + terminalEpisodes)
        appendLine("truncatedEpisodes=" + truncatedEpisodes)
        appendLine("totalExternalTransitions=" + totalExternalTransitions)
        appendLine("maxEpisodeTransitions=" + (episodeTransitions.maxOrNull() ?: 0))
        appendLine("commanderZoneDecisions=" + commanderZoneDecisions)
        appendLine("paymentDecisions=" + paymentDecisions)
        appendLine("searchDecisions=" + searchDecisions)
        appendLine("combatDecisions=" + combatDecisions)
        appendLine("actionKinds=" + actionKinds)
        appendLine("decisionFamilies=" + decisionFamilies)
        val kinds = listOf(
            "UNSUPPORTED_CARD",
            "UNSUPPORTED_DECISION",
            "UNSUPPORTED_RULE_OR_MECHANIC",
            "NATIVE_POLICY_FALLBACK",
        ).associateWith { diagnosticKinds[it] ?: 0 }
        val codes = linkedMapOf(
            "CARD_DEFINITION_MISSING" to (diagnosticCodes["CARD_DEFINITION_MISSING"] ?: 0),
            "STRUCTURED_DECISION_DOMAIN_MISSING" to
                (diagnosticCodes["STRUCTURED_DECISION_DOMAIN_MISSING"] ?: 0),
            "CHAIN_COPY_COST_UNSUPPORTED" to
                (diagnosticCodes["CHAIN_COPY_COST_UNSUPPORTED"] ?: 0),
            "ANY_PLAYER_MAY_PAY_COST_UNSUPPORTED" to
                (diagnosticCodes["ANY_PLAYER_MAY_PAY_COST_UNSUPPORTED"] ?: 0),
            "SACRIFICE_AND_PAY_COST_UNSUPPORTED" to
                (diagnosticCodes["SACRIFICE_AND_PAY_COST_UNSUPPORTED"] ?: 0),
            "PREVENT_DAMAGE_CONFIGURATION_UNSUPPORTED" to
                (diagnosticCodes["PREVENT_DAMAGE_CONFIGURATION_UNSUPPORTED"] ?: 0),
            "LIBRARY_DESTINATION_UNSUPPORTED" to
                (diagnosticCodes["LIBRARY_DESTINATION_UNSUPPORTED"] ?: 0),
            "ACTIVATED_ABILITY_SHAPE_UNSUPPORTED" to
                (diagnosticCodes["ACTIVATED_ABILITY_SHAPE_UNSUPPORTED"] ?: 0),
            "SKIP_NEXT_DRAW_TARGET_UNSUPPORTED" to
                (diagnosticCodes["SKIP_NEXT_DRAW_TARGET_UNSUPPORTED"] ?: 0),
            "TRUSTED_NATIVE_POLICY_FALLBACK" to
                (diagnosticCodes["TRUSTED_NATIVE_POLICY_FALLBACK"] ?: 0),
        )
        diagnosticCodes.filterKeys { it !in codes }.forEach { (code, count) ->
            codes[code] = count
        }
        appendLine("diagnosticCountsByKind=" + kinds)
        appendLine("diagnosticCountsByCode=" + codes)
        appendLine("firstFailure=" + (firstFailure ?: "none"))
    }
}
