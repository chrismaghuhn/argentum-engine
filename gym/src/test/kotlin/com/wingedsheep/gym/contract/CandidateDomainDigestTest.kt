package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val FIXTURE_SEED = 70L

class CandidateDomainDigestTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = FIXTURE_SEED,
            )
        )
        return env
    }

    fun observation(env: GameEnvironment): TrainingObservation = ObservationBuilder(
        cardRegistry = registry()
    ).build(
        env.state,
        env.playerIds.first(),
        env.legalActions(),
    ).observation as TrainingObservation

    fun structuredObservation(
        env: GameEnvironment,
        domain: StructuredDecisionDomain?,
        kind: PendingDecisionKind = PendingDecisionKind.REORDER_LIBRARY,
        shape: DecisionShape = DecisionShape(),
    ): TrainingObservation {
        val source = env.state.getHand(env.playerIds.first()).first()
        val trigger = env.state.getHand(env.playerIds.first()).last()
        val pending = PendingDecisionView(
            decisionId = "routing-decision",
            kind = kind,
            playerId = env.playerIds.first(),
            prompt = "presentation prompt",
            sourceEntityId = source,
            sourceName = "presentation source",
            triggeringEntityId = trigger,
            effectHint = "presentation hint",
            requiresStructuredResponse = true,
            shape = shape,
            structuredDomain = domain,
        )
        return observation(env).copy(
            pendingDecision = pending,
            legalActions = emptyList(),
        )
    }

    fun canonicalJson(domain: CompleteLegalDomainV1): String = domain.canonicalJson()

    test("action IDs do not change an action candidate domain or its digest") {
        val env = environment()
        val base = observation(env)
        val changed = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(actionId = index + 1000)
            }
        )

        val baseDomain = CompleteLegalDomainV1.from(base)
        baseDomain.kind shouldBe CompleteLegalDomainKind.ACTION_CANDIDATES
        baseDomain.candidates.size shouldBe base.legalActions.size
        baseDomain shouldBe CompleteLegalDomainV1.from(changed)
        CandidateDomainDigestV1.from(base).value shouldBe CandidateDomainDigestV1.from(changed).value
        canonicalJson(baseDomain).contains("actionId").shouldBeFalse()
    }

    test("an empty action domain remains distinguishable from a missing domain") {
        val env = environment()
        val empty = observation(env).copy(legalActions = emptyList())
        val domain = CompleteLegalDomainV1.from(empty)

        domain.kind shouldBe CompleteLegalDomainKind.ACTION_CANDIDATES
        domain.candidates shouldBe emptyList()
        canonicalJson(domain).contains("candidates").shouldBeTrue()
    }

    test("the domain digest does not sort a producer-owned candidate sequence") {
        val env = environment()
        val source = observation(env)
        val firstCandidate = source.legalActions.first()
        val secondCandidate = firstCandidate.copy(
            actionId = firstCandidate.actionId + 1,
            affordable = !firstCandidate.affordable,
        )
        val first = source.copy(legalActions = listOf(firstCandidate, secondCandidate))
        val reordered = first.copy(legalActions = first.legalActions.reversed())
        val firstDomain = CompleteLegalDomainV1.from(first)
        val reorderedDomain = CompleteLegalDomainV1.from(reordered)

        firstDomain.candidates shouldNotBe reorderedDomain.candidates
        firstDomain.canonicalJson() shouldNotBe reorderedDomain.canonicalJson()
        CandidateDomainDigestV1.from(firstDomain).value shouldNotBe
            CandidateDomainDigestV1.from(reorderedDomain).value
    }

    test("genuinely unordered candidate fields canonicalize without changing the digest") {
        val env = environment()
        val candidate = observation(env).legalActions.first()
        val first = observation(env).copy(
            legalActions = listOf(candidate.copy(targetEntityIds = listOf(EntityId("a"), EntityId("b"))))
        )
        val second = observation(env).copy(
            legalActions = listOf(candidate.copy(targetEntityIds = listOf(EntityId("b"), EntityId("a"))))
        )

        CandidateDomainDigestV1.from(first).value shouldBe CandidateDomainDigestV1.from(second).value
    }

    test("Rules-significant structured producer order changes the domain digest") {
        val env = environment()
        val first = structuredObservation(
            env,
            ReorderLibraryDomain(
                cards = listOf(EntityId("top"), EntityId("bottom")),
                cardInfo = emptyMap(),
            )
        )
        val second = first.copy(
            pendingDecision = first.pendingDecision!!.copy(
                structuredDomain = ReorderLibraryDomain(
                    cards = listOf(EntityId("bottom"), EntityId("top")),
                    cardInfo = emptyMap(),
                )
            )
        )

        CandidateDomainDigestV1.from(first).value shouldNotBe CandidateDomainDigestV1.from(second).value
    }

    test("semantic candidate payload changes the domain digest") {
        val env = environment()
        val base = observation(env)
        val candidateIndex = base.legalActions.indexOfFirst { it.actionSemantics != null }
        candidateIndex shouldNotBe -1
        val candidate = base.legalActions[candidateIndex]
        val changedSemantics = buildJsonObject {
            checkNotNull(candidate.actionSemantics).forEach { (key, value) -> put(key, value) }
            put("type", JsonPrimitive("CandidatePayloadVariant"))
        }
        val changed = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                if (index == candidateIndex) action.copy(actionSemantics = changedSemantics) else action
            }
        )

        CandidateDomainDigestV1.from(base).value shouldNotBe CandidateDomainDigestV1.from(changed).value
    }

    test("presentation-only candidate descriptions do not change the domain digest") {
        val env = environment()
        val base = observation(env)
        val changed = base.copy(
            legalActions = base.legalActions.map { it.copy(description = "different description") }
        )

        CandidateDomainDigestV1.from(base).value shouldBe CandidateDomainDigestV1.from(changed).value
    }

    test("duplicate semantic candidates are rejected instead of deduplicated") {
        val env = environment()
        val candidate = observation(env).legalActions.first()
        val duplicate = candidate.copy(actionId = candidate.actionId + 100)
        val observation = observation(env).copy(legalActions = listOf(candidate, duplicate))

        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(observation) }
    }

    test("unknown complete-domain versions fail closed") {
        val env = environment()
        val domain = CompleteLegalDomainV1.from(observation(env))
        val json = Json { encodeDefaults = true; explicitNulls = true; allowStructuredMapKeys = true }
        val encoded = json.encodeToString(CompleteLegalDomainV1.serializer(), domain)
        val unknownVersion = buildJsonObject {
            json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
            put("version", JsonPrimitive(COMPLETE_LEGAL_DOMAIN_VERSION + 1))
        }.toString()

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString(CompleteLegalDomainV1.serializer(), unknownVersion)
        }
    }

    test("unknown candidate-digest versions fail closed") {
        val digest = CandidateDomainDigestV1(
            version = CANDIDATE_DOMAIN_DIGEST_VERSION,
            schemaIdentity = CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY,
            value = "0".repeat(64),
        )
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val encoded = json.encodeToString(CandidateDomainDigestV1.serializer(), digest)
        val unknownVersion = buildJsonObject {
            json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
            put("version", JsonPrimitive(CANDIDATE_DOMAIN_DIGEST_VERSION + 1))
        }.toString()

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString(CandidateDomainDigestV1.serializer(), unknownVersion)
        }
    }

    test("structured-domain semantic changes alter the domain digest") {
        val env = environment()
        val first = structuredObservation(
            env,
            ModeSelectionDomain(
                modes = listOf(ModeOptionDomain(index = 0, text = "same", available = true)),
                minModes = 1,
                maxModes = 1,
            ),
            kind = PendingDecisionKind.CHOOSE_MODE,
        )
        val second = first.copy(
            pendingDecision = first.pendingDecision!!.copy(
                structuredDomain = ModeSelectionDomain(
                    modes = listOf(ModeOptionDomain(index = 0, text = "same", available = false)),
                    minModes = 1,
                    maxModes = 1,
                )
            )
        )

        CandidateDomainDigestV1.from(first).value shouldNotBe CandidateDomainDigestV1.from(second).value
    }

    test("missing structured domain for an acting structured decision fails closed") {
        val env = environment()
        val missing = structuredObservation(env, domain = null)

        shouldThrow<IllegalArgumentException> { CompleteLegalDomainV1.from(missing) }
    }

    test("folded decision options retain a complete candidate domain") {
        val env = environment()
        val pending = PendingDecisionView(
            decisionId = "folded-routing-id",
            kind = PendingDecisionKind.YES_NO,
            playerId = env.playerIds.first(),
            prompt = "presentation prompt",
            requiresStructuredResponse = false,
            shape = DecisionShape(),
        )
        val base = observation(env).copy(
            pendingDecision = pending,
            legalActions = listOf(
                LegalActionView(
                    actionId = 0,
                    kind = "DECISION",
                    description = "Yes",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", true)
                    },
                    isDecisionOption = true,
                ),
                LegalActionView(
                    actionId = 1,
                    kind = "DECISION",
                    description = "No",
                    affordable = true,
                    actionSemantics = buildJsonObject {
                        put("type", "YesNoResponse")
                        put("choice", false)
                    },
                    isDecisionOption = true,
                ),
            ),
        )
        val domain = CompleteLegalDomainV1.from(base)

        domain.kind shouldBe CompleteLegalDomainKind.FOLDED_DECISION_OPTIONS
        domain.candidates.size shouldBe 2
        canonicalJson(domain).contains("decisionId").shouldBeFalse()
    }

    test("structured decisions retain the typed public domain without legal-action duplication") {
        val env = environment()
        val source = structuredObservation(
            env,
            ModeSelectionDomain(
                modes = listOf(ModeOptionDomain(index = 0, text = "yes", available = true)),
                minModes = 1,
                maxModes = 1,
            ),
            kind = PendingDecisionKind.CHOOSE_MODE,
        )
        val domain = CompleteLegalDomainV1.from(source)

        domain.kind shouldBe CompleteLegalDomainKind.STRUCTURED_DECISION
        domain.structuredDomain.shouldNotBeNull()
        val canonical = canonicalJson(domain)
        canonical.contains("legalActions").shouldBeFalse()
        canonical.contains("text").shouldBeFalse()
        canonical.contains("description").shouldBeFalse()
        PlayerObservationV1.from(source).canonicalJson().contains("structuredDomain").shouldBeFalse()
    }

    test("existing StateDigest and TrainingObservation semantics remain unchanged") {
        val env = environment()
        val source = observation(env)

        StateDigest.compute(source) shouldBe source.stateDigest
        ObservationCanonicalizer.wireJson(source).contains("legalActions").shouldBeTrue()
    }
})
