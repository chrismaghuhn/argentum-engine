package com.wingedsheep.gym

import com.wingedsheep.engine.core.BudgetModalResponse
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ColorChosenResponse
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.NumberChosenResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.ReplacementChosenResponse
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.StepRequest
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

private const val EXACT_PAIR_PREFIX_MAX_STEPS = 2_000

/** Real locked-pair regression for the first A9 chosen-input blocker. */
class EnvironmentV1ExactPairChosenCostPaymentTest : FunSpec({

    fun exactPairRepositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent
                ?: error("Could not locate the repository root from ${System.getProperty("user.dir")}")
        }
        return current
    }

    fun lockedDeck(fileName: String): ExactPairLockedDeck =
        exactPairRepositoryRoot().resolve("docs/ml/curriculum").resolve(fileName)
            .readLines()
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }
            .let { cards -> ExactPairLockedDeck(commander = cards.first(), cards = cards) }

    fun registry() = com.wingedsheep.engine.registry.CardRegistry().apply {
        MtgSetCatalog.all.forEach { set ->
            register(set.cards)
            register(set.basicLands)
        }
    }

    fun config(): EnvConfig {
        val decks = mapOf(
            "Akiri" to lockedDeck("akiri-v0.1.txt"),
            "Chevill" to lockedDeck("chevill-v0.1.txt"),
        )
        fun player(name: String): PlayerSpec {
            val deck = decks.getValue(name)
            return PlayerSpec(
                name = name,
                deck = DeckSpec.Explicit(deck.cards.drop(1).groupingBy { it }.eachCount()),
                startingLife = 40,
                commanderCardName = deck.commander,
            )
        }
        return EnvConfig(
            players = listOf(player("Akiri"), player("Chevill")),
            format = Format.Commander(),
            startingHandSize = 7,
            skipMulligans = true,
            useHandSmoother = false,
            startingPlayerIndex = 0,
            seed = 0L,
            maxSteps = EXACT_PAIR_PREFIX_MAX_STEPS,
            perspectivePlayerIndex = 0,
        )
    }

    test("real exact-pair costPayment action crosses chosen semantic validation") {
        val service = MultiEnvService(registry())
        val created = service.create(config())
        try {
            var current = created.observation
            var observation = current.observation as TrainingObservation
            val policy = DeterministicExternalPolicy()
            var policyState = DeterministicPolicyState(policySeed = 4_259_905L)
            var transitions = 0
            var chosen: ChosenSemanticActionV1? = null

            while (chosen == null && !observation.terminated && !observation.truncated) {
                check(transitions < EXACT_PAIR_PREFIX_MAX_STEPS) {
                    "Exact-pair prefix did not reach costPayment before maxSteps"
                }
                val selection = policy.choose(observation, policyState)
                policyState = policyState.afterChoice()
                current = when (selection) {
                    is SemanticChoice.Action -> {
                        val view = observation.legalActions.singleOrNull {
                            it.actionId == selection.actionId
                        } ?: error("Exact-pair policy action was absent from the public list")

                        if (selection.payload?.containsKey("costPayment") == true) {
                            val candidate = ObservationCanonicalizer.semanticActionFingerprint(view)
                            val domain = CompleteLegalDomainV1.from(observation)
                            val resolved = current.registry.resolve(selection.actionId)
                            val template = (resolved as? ResolvedAction.Legal)?.action
                                ?: error("Real costPayment action did not resolve to a legal GameAction")
                            val recorded = recordAction(template, selection.payload)

                            val accepted = ChosenSemanticActionV1.fromRecordedAction(
                                domain = domain,
                                candidate = candidate,
                                action = recorded,
                            )
                            accepted.candidate shouldBe candidate
                            accepted.choicePayload["costPayment"] shouldBe
                                A3SemanticJson.strictJson.encodeToJsonElement(
                                    AdditionalCostPayment.serializer(),
                                    (recorded as com.wingedsheep.engine.core.ActivateAbility).costPayment
                                        ?: error("Real costPayment action lost its payment"),
                                )
                            chosen = accepted
                            current
                        } else {
                            service.step(
                                StepRequest(
                                    envId = created.envId,
                                    actionId = selection.actionId,
                                    action = selection.payload,
                                ),
                            )
                        }
                    }

                    is SemanticChoice.Structured -> {
                        val pending = observation.pendingDecision
                            ?: error("Structured exact-pair choice had no pending decision")
                        val decisionId = pending.decisionId
                            ?: error("Structured exact-pair choice had no decision ID")
                        service.submitDecision(
                            envId = created.envId,
                            response = selection.toDecisionResponse(decisionId),
                            actorId = observation.agentToAct,
                        )
                    }

                    is SemanticChoice.Gap ->
                        error("Exact-pair prefix reached a policy gap before costPayment: $selection")
                }
                observation = current.observation as TrainingObservation
                transitions++
            }

            check(chosen != null) {
                "The locked exact pair did not reach a public costPayment action"
            }
        } finally {
            service.dispose(listOf(created.envId))
        }
    }
})

private fun SemanticChoice.Structured.toDecisionResponse(decisionId: String): DecisionResponse =
    when (val choice = selection) {
        is SemanticDecision.Targets -> TargetsResponse(decisionId, choice.selected)
        is SemanticDecision.Cards -> CardsSelectedResponse(decisionId, choice.selected)
        is SemanticDecision.Modes -> ModesChosenResponse(decisionId, choice.selected)
        is SemanticDecision.Color -> ColorChosenResponse(decisionId, choice.selected)
        is SemanticDecision.Number -> NumberChosenResponse(decisionId, choice.selected)
        is SemanticDecision.Distribution -> DistributionResponse(decisionId, choice.selected)
        is SemanticDecision.Ordered -> OrderedResponse(decisionId, choice.selected)
        is SemanticDecision.Piles -> PilesSplitResponse(decisionId, choice.selected)
        is SemanticDecision.Option -> OptionChosenResponse(decisionId, choice.selected)
        is SemanticDecision.Replacement -> ReplacementChosenResponse(decisionId, choice.from, choice.to)
        is SemanticDecision.Budget -> BudgetModalResponse(decisionId, choice.selected)
        is SemanticDecision.Damage -> CombatResolutionResponse(
            decisionId = decisionId,
            edges = choice.selected.map { DamageEdgeAmount(it.edgeId, it.amount) },
        )
        is SemanticDecision.Payment -> choice.toDecisionResponse(decisionId)
    }

private fun recordAction(template: GameAction, payload: kotlinx.serialization.json.JsonObject): GameAction {
    val templateJson = A3SemanticJson.strictJson
        .encodeToJsonElement(GameAction.serializer(), template)
        .jsonObject
    val merged = buildJsonObject {
        templateJson.forEach { (key, value) -> put(key, value) }
        payload.forEach { (key, value) ->
            if (key != "abilityKey") put(key, value)
        }
    }
    return A3SemanticJson.strictJson.decodeFromJsonElement(GameAction.serializer(), merged)
}

private data class ExactPairLockedDeck(
    val commander: String,
    val cards: List<String>,
)
