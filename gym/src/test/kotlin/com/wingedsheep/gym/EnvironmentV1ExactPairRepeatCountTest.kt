package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.A3SemanticJson
import com.wingedsheep.gym.contract.ChosenSemanticActionV1
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.ResolvedAction
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

private const val EXACT_PAIR_REPEAT_ENGINE_SEED = 2L
private const val EXACT_PAIR_REPEAT_POLICY_SEED = 6_259_911L

/** Real locked-card Rules producer witness for the repeat-count chosen-input contract. */
class EnvironmentV1ExactPairRepeatCountTest : FunSpec({
    test("locked exact-pair repeatable Akiri action crosses stored repeat-count validation") {
        val registry = exactPairRegistry()
        val environment = GameEnvironment.create(registry)
        environment.reset(exactPairConfig(), maxSteps = 2_000)

        var state = environment.state
        while (state.step != com.wingedsheep.sdk.core.Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.action is PassPriority }
            environment.step(pass.action)
            state = environment.state
        }

        val player = environment.playerIds.first()
        fun moveNamed(name: String): EntityId {
            val cardId = state.entities.entries.first { (id, container) ->
                id in state.getZone(player, Zone.HAND) +
                    state.getZone(player, Zone.LIBRARY) +
                    state.getZone(player, Zone.COMMAND) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> cardId in ids }.key
            state = state.moveToZone(cardId, sourceZone, ZoneKey(player, Zone.BATTLEFIELD))
            return cardId
        }

        val akiri = moveNamed("Akiri, Fearless Voyager")
        val equipment = moveNamed("Bonesplitter")
        repeat(3) { moveNamed("Plains") }
        state = state.updateEntity(equipment) { container ->
            container.with(AttachedToComponent(akiri))
        }
        state = state.updateEntity(akiri) { container ->
            container.with(AttachmentsComponent(listOf(equipment)))
        }
        environment.restore(state, environment.playerIds, environment.stepCount, maxSteps = 2_000)

        val observationResult = ObservationBuilder(cardRegistry = registry).build(
            state = environment.state,
            perspectivePlayerId = player,
            legalActions = environment.legalActions(),
        )
        val observation = observationResult.observation as TrainingObservation
        val repeatView = observation.legalActions.firstOrNull { it.repeatCountDomain != null }
            ?: error("Locked Akiri setup did not expose a repeat-count domain")
        repeatView.requiredPayloadFields.contains("repeatCount") shouldBe true
        val repeatDomain = checkNotNull(repeatView.repeatCountDomain)
        repeatDomain.minCount shouldBe 1
        check(repeatDomain.maxCount > 1)

        val publicObservation = observation.copy(legalActions = listOf(repeatView))
        val choice = DeterministicExternalPolicy().choose(
            publicObservation,
            DeterministicPolicyState(policySeed = EXACT_PAIR_REPEAT_POLICY_SEED),
        )
        val actionChoice = choice as? SemanticChoice.Action
            ?: error("Locked repeat-count witness did not produce an external action: $choice")
        val payload = actionChoice.payload
            ?: error("Locked repeat-count witness did not produce a structured payload")
        val candidate = ObservationCanonicalizer.semanticActionFingerprint(repeatView)
        val domain = CompleteLegalDomainV1.from(publicObservation)
        val resolved = observationResult.registry.resolve(repeatView.actionId)
        val resolvedLegal = resolved as? ResolvedAction.Legal
            ?: error("Locked repeat-count witness did not resolve a legal action")
        resolvedLegal.legalAction.maxRepeatableActivations shouldBe repeatDomain.maxCount
        val template = resolvedLegal.action
        val recorded = recordAction(template, payload)
        val recordedRepeat = (recorded as? ActivateAbility)?.repeatCount
            ?: error("Locked repeat-count witness was not an ActivateAbility")
        check(recordedRepeat in repeatDomain.minCount..repeatDomain.maxCount)

        val chosen = ChosenSemanticActionV1.fromRecordedAction(domain, candidate, recorded)
        chosen.candidate shouldBe candidate
        chosen.choicePayload["repeatCount"] shouldBe payload["repeatCount"]
    }
})

private fun exactPairConfig(): GameConfig {
    fun lockedDeck(fileName: String): List<String> = repositoryRoot()
        .resolve("docs/ml/curriculum")
        .resolve(fileName)
        .readLines()
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }

    val akiri = lockedDeck("akiri-v0.1.txt")
    val chevill = lockedDeck("chevill-v0.1.txt")
    fun player(name: String, deck: List<String>, id: String): PlayerConfig = PlayerConfig(
        name = name,
        deck = Deck(deck.drop(1)),
        startingLife = 40,
        playerId = EntityId(id),
        commanderCardName = deck.first(),
    )
    return GameConfig(
        players = listOf(
            player("Akiri", akiri, "a9-replay-player-0"),
            player("Chevill", chevill, "a9-replay-player-1"),
        ),
        format = Format.Commander(),
        startingHandSize = 7,
        skipMulligans = true,
        useHandSmoother = false,
        startingPlayerIndex = 0,
        seed = EXACT_PAIR_REPEAT_ENGINE_SEED,
    )
}

private fun exactPairRegistry() = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun repositoryRoot(): Path {
    var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    while (!Files.exists(current.resolve("settings.gradle.kts"))) {
        current = current.parent ?: error("Could not locate the repository root")
    }
    return current
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
