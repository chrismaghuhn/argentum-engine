package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardDefinitionMissingException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.contract.ObservationResult
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.PaymentCostKind
import com.wingedsheep.gym.contract.PaymentCostUnitDomain
import com.wingedsheep.gym.contract.PaymentDomainV2
import com.wingedsheep.gym.contract.PaymentPoolDomainV2
import com.wingedsheep.gym.contract.PaymentSourceActivationDomain
import com.wingedsheep.gym.contract.LegalActionView
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
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
            "ManaSolver",
            "AutomaticPaymentSelection",
            "AutoPay",
            "autoPaySuggestion",
            "autoTapSuggestion",
        ).forEach { forbidden ->
            check(forbidden !in source) {
                "Observation-only acceptance policy contains forbidden symbol: $forbidden"
            }
        }
    }

    test("the external policy turns the public payment domain into an explicit PaymentPlanV1") {
        val player = EntityId("player-0")
        val blackSource = EntityId("source-black")
        val anySource = EntityId("source-any")
        val paymentDomain = PaymentDomainV2(
            requiredCost = "{1}{B}",
            costUnits = listOf(
                PaymentCostUnitDomain(0, PaymentCostKind.GENERIC, amount = 1),
                PaymentCostUnitDomain(
                    symbolIndex = 1,
                    kind = PaymentCostKind.COLORED,
                    amount = 1,
                    allowedColors = setOf(PaymentManaColor.BLACK),
                ),
            ),
            currentPool = PaymentPoolDomainV2(),
            sourceActivations = listOf(
                PaymentSourceActivationDomain(
                    sourceId = blackSource,
                    sourceName = "Black Source",
                    manaAbilityKey = "black-ability",
                    productionChoices = listOf(ProductionChoice(PaymentManaColor.BLACK)),
                ),
                PaymentSourceActivationDomain(
                    sourceId = anySource,
                    sourceName = "Any Source",
                    manaAbilityKey = "any-ability",
                    productionChoices = listOf(
                        ProductionChoice(PaymentManaColor.BLACK),
                        ProductionChoice(PaymentManaColor.GREEN),
                    ),
                ),
            ),
        )
        val action = LegalActionView(
            actionId = 7,
            kind = "ActivateAbility",
            description = "Activate public payment-domain source",
            affordable = true,
            manaCost = "{1}{B}",
            paymentDomain = paymentDomain,
            requiresStructuredAction = true,
            actionSemantics = buildJsonObject {
                put("type", "ActivateAbility")
                put("abilityKey", "ability-1")
            },
        )
        val observation = TrainingObservation(
            schemaHash = "test-schema",
            perspectivePlayerId = player,
            agentToAct = player,
            turnNumber = 1,
            phase = com.wingedsheep.sdk.core.Phase.PRECOMBAT_MAIN,
            step = com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN,
            activePlayerId = player,
            priorityPlayerId = player,
            players = emptyList(),
            zones = emptyList(),
            stack = emptyList(),
            pendingDecision = null,
            legalActions = listOf(action),
            terminated = false,
            truncated = false,
            winnerId = null,
            stateDigest = "digest",
        )

        val choice = DeterministicExternalPolicy().choose(
            observation,
            DeterministicPolicyState(policySeed = 1L),
        )
        check(choice is SemanticChoice.Action) { "Expected an action choice, got $choice" }
        val payload = choice.payload ?: error("Payment action did not publish a payload")
        val strategy = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }.decodeFromJsonElement(
            PaymentStrategy.serializer(),
            payload["paymentStrategy"] ?: error("Payment payload omitted paymentStrategy"),
        )
        check(strategy is PaymentStrategy.Explicit) { "Expected Explicit payment strategy: $strategy" }
        check(strategy.manaAbilitiesToActivate.isEmpty()) {
            "Payment policy must not emit legacy source handles"
        }
        val plan = strategy.paymentPlan ?: error("Payment policy omitted PaymentPlanV1")
        plan.sourceActivations.map { it.sourceId }.toSet() shouldBe setOf(blackSource, anySource)
        plan.spendAllocation.costUnits.map { it.symbolIndex } shouldBe listOf(0, 1)
        plan.spendAllocation.costUnits.sumOf { it.spends.sumOf { spend -> spend.amount } } shouldBe 2
    }

    test("seed zero original reproducer stops at the first current finding") {
        val service = MultiEnvService(exactPairRegistry())
        try {
            val result = runEpisode(
                service = service,
                policy = DeterministicExternalPolicy(),
                episode = EpisodeConfig(
                    seed = 0L,
                    startingPlayerIndex = 0,
                    seat0 = "Akiri",
                    seat1 = "Chevill",
                    rosterLabel = "Akiri-vs-Chevill",
                ),
            )
            println("ENVIRONMENT_V1_SEED_ZERO_REPRODUCER\n$result")
            result.failure?.let { failure ->
                error("Seed-zero reproducer stopped at the first current finding: $failure")
            }
        } finally {
            service.dispose(service.listEnvs())
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
            "0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6"
        const val CHEVILL_SHA256 =
            "D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2"
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
                diagnostic: String = code,
                publicDomain: String = "not captured",
                proposedFollowUp: String = "No follow-up recorded",
            ): AcceptanceFailure = AcceptanceFailure(
                classification = classification,
                code = code,
                reason = reason,
                diagnostic = diagnostic,
                publicDomain = publicDomain,
                proposedFollowUp = proposedFollowUp,
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
                    reason = when (signal.semanticCode) {
                        "PAYMENT_DOMAIN_UNSUPPORTED" ->
                            "Trusted transition reached a payable legal action without a published PaymentDomainV2"
                        else -> "Authoritative trusted-episode diagnostic was recorded"
                    },
                    diagnostic = signal.semanticCode,
                    publicDomain = when (signal.semanticCode) {
                        "PAYMENT_DOMAIN_UNSUPPORTED" ->
                            "LegalActionView.paymentDomain=null; post-transition observation was not published"
                        else -> "authoritative diagnostic event; public domain not captured"
                    },
                    proposedFollowUp = when (signal.semanticCode) {
                        "PAYMENT_DOMAIN_UNSUPPORTED" ->
                            "Publish a complete PaymentDomainV2 for every reachable payable legal action outside #73"
                        else -> "Classify and repair the owning production path outside #73"
                    },
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
                            lastActionKind = choice.actionKind ?: "DECISION"
                            val failure = currentFailure(
                                classification = choice.classification,
                                code = choice.code,
                                reason = choice.reason,
                                diagnostic = choice.diagnostic,
                                publicDomain = choice.publicDomain,
                                proposedFollowUp = choice.proposedFollowUp,
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

        fun sha256(path: Path): String {
            val canonicalBytes = path.readText().replace("\r\n", "\n").toByteArray()
            return MessageDigest.getInstance("SHA-256")
                .digest(canonicalBytes)
                .joinToString("") { byte -> "%02X".format(byte) }
        }
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
    val diagnostic: String,
    val publicDomain: String,
    val proposedFollowUp: String,
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
    override fun toString(): String = listOf(
        "CLASSIFICATION: $classification",
        "SEED: $seed",
        "POLICY_SEED: $policySeed",
        "ROSTER: $roster",
        "STARTING_PLAYER: $startingPlayerIndex",
        "EXTERNAL_STEP: $step",
        "ACTOR: ${actor ?: "null"}",
        "STATE_DIGEST: ${stateDigest ?: "null"}",
        "ACTION_KIND: $actionKind",
        "LAST_DECISION_FAMILY: $decisionFamily",
        "DIAGNOSTIC: $diagnostic (code=$code)",
        "PUBLIC_DOMAIN: $publicDomain",
        "ROOT_CAUSE: $reason",
        "PROPOSED_FOLLOW_UP: $proposedFollowUp",
    ).joinToString("\n")
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
            val kind = when (failure.classification) {
                "A9_UNSUPPORTED_CARD" -> "UNSUPPORTED_CARD"
                "A9_UNSUPPORTED_DECISION" -> "UNSUPPORTED_DECISION"
                "A9_UNSUPPORTED_RULE_OR_MECHANIC" -> "UNSUPPORTED_RULE_OR_MECHANIC"
                "A5_NATIVE_POLICY_FALLBACK" -> "NATIVE_POLICY_FALLBACK"
                else -> null
            }
            kind?.let { diagnosticKinds[it] = (diagnosticKinds[it] ?: 0) + 1 }
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
            "PAYMENT_DOMAIN_UNSUPPORTED" to
                (diagnosticCodes["PAYMENT_DOMAIN_UNSUPPORTED"] ?: 0),
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
