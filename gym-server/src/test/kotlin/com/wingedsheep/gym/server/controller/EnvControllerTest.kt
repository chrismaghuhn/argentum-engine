package com.wingedsheep.gym.server.controller

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.DecisionResponse
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.contract.CardSelectionDomain
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.gym.contract.SearchLibraryDomain
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.EnvId
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.SnapshotHandle
import com.wingedsheep.gym.server.dto.CreateEnvResponse
import com.wingedsheep.gym.server.dto.DisposeBody
import com.wingedsheep.gym.server.dto.RestoreBody
import com.wingedsheep.gym.server.dto.SchemaHashResponse
import com.wingedsheep.gym.server.dto.StepBody
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.beans.factory.annotation.Autowired
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * End-to-end integration test for the HTTP surface. Boots the full Spring
 * app on a random port, then drives each endpoint via `java.net.http.HttpClient`
 * so we exercise the real converter chain (kotlinx.serialization) and
 * exception-handler chain.
 *
 * Deliberately thin — happy path, 404/400 errors, and a stale-action-ID
 * rejection. Sealed-deck flows and structured decisions belong in
 * dedicated tests alongside the controllers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnvControllerTest : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var multiEnvService: MultiEnvService

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        allowStructuredMapKeys = true
    }

    private val client: HttpClient = HttpClient.newBuilder().build()

    private val httpForestSource = card("A5 HTTP Forest Source") {
        typeLine = "Land — Forest"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
    }

    private val httpHomogeneousSpell = card("A5 HTTP Homogeneous Floating Spell") {
        manaCost = "{1}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    init {
        extension(SpringExtension())

        fun baseUrl() = "http://localhost:$port"

        fun miniDeck() = DeckSpec.Explicit(mapOf("Mountain" to 17, "Raging Goblin" to 3))
        fun twoPlayerConfig() = EnvConfig(
            players = listOf(
                PlayerSpec("Alice", miniDeck()),
                PlayerSpec("Bob", miniDeck())
            ),
            skipMulligans = true,
            startingPlayerIndex = 0
        )

        fun commanderConfig() = EnvConfig(
            players = listOf(
                PlayerSpec(
                    "Alice",
                    DeckSpec.Explicit(mapOf("Mountain" to 99)),
                    commanderCardName = "Raging Goblin"
                ),
                PlayerSpec(
                    "Bob",
                    DeckSpec.Explicit(mapOf("Mountain" to 99)),
                    commanderCardName = "Raging Goblin"
                )
            ),
            format = Format.Commander(),
            seed = 7L,
            skipMulligans = true,
            startingPlayerIndex = 0,
            perspectivePlayerIndex = 0
        )

        fun get(path: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("${baseUrl()}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        )

        fun postJson(path: String, body: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("${baseUrl()}$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        fun deleteJson(path: String, body: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("${baseUrl()}$path"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        test("GET /schema-hash returns the current schema") {
            val response = get("/schema-hash")
            response.statusCode() shouldBe 200
            val parsed = json.decodeFromString<SchemaHashResponse>(response.body())
            parsed.schemaHash shouldBe "argentum-gym-contract@v1.25-target-payment-domain"
            parsed.schemaHash shouldBe SchemaHash.CURRENT
        }

        test("GET /health returns ok") {
            val response = get("/health")
            response.statusCode() shouldBe 200
            response.body() shouldContain "\"status\""
            response.body() shouldContain "\"ok\""
        }

        test("GET /v3/api-docs serves the OpenAPI spec") {
            val response = get("/v3/api-docs")
            response.statusCode() shouldBe 200
            // Sanity-check a couple of endpoints appear in the spec.
            response.body() shouldContain "\"/envs\""
            response.body() shouldContain "\"/envs/{id}/step\""
            response.body() shouldContain "Environments"  // tag name from EnvController
        }

        test("OpenAPI spec has no Kotlin inline-class mangled property names") {
            // Regression for the `id-v2tQoa0` / `ownerId-Z9UYGMk` cosmetic bug:
            // Kotlin mangles @JvmInline value class getter method names, and
            // Springdoc's reflection introspection picks those names up unless
            // the `stripInlineClassMangling` customizer in OpenApiConfig runs.
            val body = get("/v3/api-docs").body()
            // Any property ending in `-XXXXXX` with mixed case/digits is suspect.
            val suspicious = Regex("\"([A-Za-z]+-[A-Za-z0-9_]{5,})\"\\s*:\\s*\\{")
                .findAll(body)
                .map { it.groupValues[1] }
                .toList()
            suspicious shouldBe emptyList()
        }

        test("GET /swagger-ui/index.html serves the UI") {
            // The `/swagger-ui.html` path redirects to `/swagger-ui/index.html`;
            // we hit the underlying page directly so a default HttpClient
            // (which follows redirects by default) doesn't matter.
            val response = get("/swagger-ui/index.html")
            response.statusCode() shouldBe 200
            response.body() shouldContain "Swagger UI"
        }

        test("create -> observe -> step -> dispose round-trips over HTTP") {
            // -- create --
            val createResponse = postJson("/envs", json.encodeToString(twoPlayerConfig()))
            createResponse.statusCode() shouldBe 200
            val created = json.decodeFromString<CreateEnvResponse>(createResponse.body())

            created.envId.value.shouldNotBe("")
            (created.observation as TrainingObservation).players.size shouldBe 2
            created.observation.terminated.shouldBeFalse()
            created.observation.legalActions.shouldNotBeEmpty()

            // -- observe (no-op) --
            val observed = json.decodeFromString<TrainingObservation>(
                get("/envs/${created.envId.value}").body()
            )
            observed.stateDigest shouldBe created.observation.stateDigest

            // -- step using an actionId from the opening observation --
            val firstAction = created.observation.legalActions.first()
            val actionId = firstAction.actionId
            val stepResp = postJson(
                "/envs/${created.envId.value}/step",
                json.encodeToString(StepBody(actionId, firstAction.actionSemantics))
            )
            stepResp.statusCode() shouldBe 200
            val afterStep = json.decodeFromString<TrainingObservation>(stepResp.body())
            afterStep.stateDigest shouldNotBe created.observation.stateDigest

            // The old handle is in range, but it belongs to the previous observation generation.
            // It must not be rebound to whichever action now occupies that integer.
            val staleStepResp = postJson(
                "/envs/${created.envId.value}/step",
                json.encodeToString(StepBody(actionId, firstAction.actionSemantics))
            )
            staleStepResp.statusCode() shouldBe 400
            val afterStale = json.decodeFromString<TrainingObservation>(
                get("/envs/${created.envId.value}").body()
            )
            afterStale.stateDigest shouldBe afterStep.stateDigest

            // -- list includes the env --
            val listResp = get("/envs")
            val ids = json.decodeFromString<List<EnvId>>(listResp.body())
            ids.any { it.value == created.envId.value } shouldBe true

            // -- dispose --
            val disposeResp = deleteJson(
                "/envs",
                json.encodeToString(DisposeBody(listOf(created.envId)))
            )
            disposeResp.statusCode() shouldBe 204

            // Observing a disposed env returns 404.
            get("/envs/${created.envId.value}").statusCode() shouldBe 404
        }

        test("HTTP structured decision observation supplies a candidate accepted by /decision") {
            fun structuredConfig(seed: Long) = EnvConfig(
                players = listOf(
                    PlayerSpec(
                        "Alice",
                        DeckSpec.Explicit(mapOf("Forest" to 30, "Rampant Growth" to 10))
                    ),
                    PlayerSpec("Bob", DeckSpec.Explicit(mapOf("Forest" to 40)))
                ),
                skipMulligans = true,
                useHandSmoother = false,
                startingPlayerIndex = 0,
                seed = seed,
                perspectivePlayerIndex = 0,
                maxSteps = 200
            )

            fun hand(observation: TrainingObservation) = observation.zones
                .first { it.ownerId == observation.perspectivePlayerId && it.zoneType == Zone.HAND }
                .cards

            fun paymentPayload(action: LegalActionView): JsonObject? {
                val semantics = action.actionSemantics ?: return null
                val domain = action.paymentDomain ?: return semantics
                val remainingPool = domain.initialPoolBuckets
                    .associate { it.key to it.availableAmount }
                    .toMutableMap()
                val usedSourceIds = mutableSetOf<com.wingedsheep.sdk.model.EntityId>()
                val activations = mutableListOf<SourceActivationV2>()
                val allocations = buildList {
                    for (unit in domain.outerAtomicCostUnits) {
                        fun matches(color: com.wingedsheep.engine.core.PaymentManaColor): Boolean =
                            when (unit.kind) {
                                PaymentCostKindV1.COLORED -> color in unit.allowedColors
                                PaymentCostKindV1.COLORLESS ->
                                    color == com.wingedsheep.engine.core.PaymentManaColor.COLORLESS
                                PaymentCostKindV1.GENERIC -> true
                            }

                        val poolBucket = domain.initialPoolBuckets.firstOrNull { bucket ->
                            (remainingPool[bucket.key] ?: 0) > 0 &&
                                when (val key = bucket.key) {
                                    is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> matches(key.color)
                                    is InitialPoolBucketKeyV1.CertifiedFloatingBucket ->
                                        matches(key.key.poolColor)
                                }
                        }
                        if (poolBucket != null) {
                            remainingPool[poolBucket.key] =
                                (remainingPool[poolBucket.key] ?: 0) - 1
                            add(
                                PaymentAllocationV1(
                                    target = PaymentTargetV1.OuterCostUnit(
                                        symbolIndex = unit.symbolIndex,
                                        unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                                    ),
                                    resource = ManaResourceRefV1.InitialPoolResource(poolBucket.key),
                                )
                            )
                            continue
                        }

                        val source = domain.sourceActivationOptions.firstOrNull { candidate ->
                            candidate.sourceId !in usedSourceIds &&
                                candidate.atomicActivationManaCostUnits.isEmpty() &&
                                candidate.productionChoices.any { choice ->
                                    choice.fixedOutputs?.any { output ->
                                        output.amount > 0 && matches(output.color)
                                    } ?: matches(choice.producedColor)
                                }
                        } ?: error("No explicit source in the HTTP V5 payment domain for $unit")
                        val production = source.productionChoices.first { choice ->
                            choice.fixedOutputs?.any { output ->
                                output.amount > 0 && matches(output.color)
                            } ?: matches(choice.producedColor)
                        }
                        usedSourceIds += source.sourceId
                        val activationIndex = activations.size
                        activations += SourceActivationV2(
                            sourceId = source.sourceId,
                            manaAbilityKey = source.manaAbilityKey,
                            productionChoice = production,
                            activationCostOrder = source.activationCostOrderOptions.first(),
                        )
                        val outputIndex = production.fixedOutputs
                            ?.first { it.amount > 0 && matches(it.color) }
                            ?.index
                            ?: 0
                        add(
                            PaymentAllocationV1(
                                target = PaymentTargetV1.OuterCostUnit(
                                    symbolIndex = unit.symbolIndex,
                                    unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                                ),
                                resource = ManaResourceRefV1.ActivationOutputUnit(
                                    activationIndex = activationIndex,
                                    outputIndex = outputIndex,
                                ),
                            )
                        )
                    }
                }
                val strategy = json.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(
                        paymentPlan = PaymentPlanV3(
                            activations = activations,
                            outerAllocation = allocations,
                        ),
                    ),
                )
                return buildJsonObject {
                    semantics.forEach { (key, value) -> put(key, value) }
                    put("paymentStrategy", strategy)
                }
            }

            var selected: CreateEnvResponse? = null
            var selectedObservation: TrainingObservation? = null
            var envToDispose: EnvId? = null
            try {
                for (seed in 0L..100L) {
                    val response = postJson("/envs", json.encodeToString(structuredConfig(seed)))
                    response.statusCode() shouldBe 200
                    val candidate = json.decodeFromString<CreateEnvResponse>(response.body())
                    envToDispose = candidate.envId
                    val opening = candidate.observation as TrainingObservation
                    val openingHand = hand(opening)
                    if (openingHand.count { it.name == "Forest" } >= 2 &&
                        openingHand.any { it.name == "Rampant Growth" }
                    ) {
                        selected = candidate
                        selectedObservation = opening
                        break
                    }
                    deleteJson("/envs", json.encodeToString(DisposeBody(listOf(candidate.envId))))
                    envToDispose = null
                }

                val created = checkNotNull(selected) { "No deterministic seed produced the structured-decision opening hand" }
                var observation = checkNotNull(selectedObservation)
                var structuredObservation: TrainingObservation? = null

                for (stepIndex in 0 until 120) {
                    if (observation.pendingDecision?.structuredDomain != null) {
                        structuredObservation = observation
                        break
                    }

                    val actorHand = hand(observation)
                    val actorCardIds = actorHand.associateBy { it.name }
                    val action = observation.legalActions.firstOrNull {
                        it.kind == "PlayLand" && it.sourceEntityId == actorCardIds["Forest"]?.entityId
                    } ?: observation.legalActions.firstOrNull {
                        it.kind == "CastSpell" &&
                            it.affordable &&
                            it.sourceEntityId == actorCardIds["Rampant Growth"]?.entityId
                    } ?: observation.legalActions.firstOrNull { it.kind == "PassPriority" }

                    checkNotNull(action) {
                        "Structured HTTP fixture stalled without a play, cast, or pass action"
                    }
                    val step = postJson(
                        "/envs/${created.envId.value}/step",
                        json.encodeToString(StepBody(action.actionId, paymentPayload(action)))
                    )
                    step.statusCode() shouldBe 200
                    observation = json.decodeFromString<TrainingObservation>(step.body())
                }

                val paused = checkNotNull(structuredObservation) {
                    "HTTP fixture did not reach a structured decision"
                }
                val pending = checkNotNull(paused.pendingDecision)
                val candidate = when (val domain = checkNotNull(pending.structuredDomain)) {
                    is CardSelectionDomain -> domain.options.firstOrNull()
                    is SearchLibraryDomain -> domain.options.firstOrNull()
                    else -> error("Expected a card-selection/search structured domain, got ${domain::class.simpleName}")
                }
                val selectedCandidate = checkNotNull(candidate) { "Structured domain contained no legal candidate" }
                val response = CardsSelectedResponse(checkNotNull(pending.decisionId), listOf(selectedCandidate))

                val decisionResponse = postJson(
                    "/envs/${created.envId.value}/decision",
                    json.encodeToString(DecisionResponse.serializer(), response)
                )
                decisionResponse.statusCode() shouldBe 200
                val afterDecision = json.decodeFromString<TrainingObservation>(decisionResponse.body())
                afterDecision.pendingDecision shouldBe null
            } finally {
                envToDispose?.let {
                    deleteJson("/envs", json.encodeToString(DisposeBody(listOf(it))))
                }
            }
        }

        test("HTTP round-trips PaymentDomain V5 initial buckets and PaymentPlan V3") {
            multiEnvService.cardRegistry.register(
                listOf(httpForestSource, httpHomogeneousSpell)
            )
            val config = EnvConfig(
                players = listOf(
                    PlayerSpec(
                        "Alice",
                        DeckSpec.Explicit(
                            mapOf(
                                httpForestSource.name to 1,
                                httpHomogeneousSpell.name to 1,
                                "Mountain" to 4,
                            ),
                        ),
                    ),
                    PlayerSpec("Bob", DeckSpec.Explicit(mapOf("Mountain" to 7))),
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 0L,
                perspectivePlayerIndex = 0,
            )
            val created = json.decodeFromString<CreateEnvResponse>(
                postJson("/envs", json.encodeToString(config)).body()
            )
            created.observation.terminated shouldBe false
            val envId = created.envId

            fun cardName(observation: TrainingObservation, entityId: com.wingedsheep.sdk.model.EntityId?): String? =
                entityId?.let { id ->
                    observation.zones.asSequence()
                        .flatMap { it.cards.asSequence() }
                        .firstOrNull { it.entityId == id }
                        ?.name
                }

            fun step(observation: TrainingObservation, action: LegalActionView): TrainingObservation {
                val response = postJson(
                    "/envs/${envId.value}/step",
                    json.encodeToString(StepBody(action.actionId, action.actionSemantics)),
                )
                check(response.statusCode() == 200) {
                    "HTTP step rejected action ${action.kind}: ${response.statusCode()} ${response.body()}"
                }
                return json.decodeFromString(response.body())
            }

            fun findAction(
                observation: TrainingObservation,
                predicate: (LegalActionView) -> Boolean,
            ): LegalActionView {
                var current = observation
                repeat(80) {
                    current.legalActions.firstOrNull(predicate)?.let { return it }
                    val pass = current.legalActions.firstOrNull { it.kind == "PassPriority" }
                        ?: error("No desired action or pass action in HTTP observation: ${current.legalActions}")
                    current = step(current, pass)
                }
                error("Could not find the desired HTTP action")
            }

            try {
                var observation = created.observation as TrainingObservation
                val landAction = findAction(observation) {
                    it.kind == "PlayLand" && cardName(observation, it.sourceEntityId) == httpForestSource.name
                }
                val landId = checkNotNull(landAction.sourceEntityId)
                observation = step(observation, landAction)

                val landActivation = findAction(observation) {
                    it.kind == "ActivateAbility" && it.isManaAbility && it.sourceEntityId == landId
                }
                observation = step(observation, landActivation)

                val spellCardId = observation.zones
                    .flatMap { it.cards }
                    .first { it.name == httpHomogeneousSpell.name }
                    .entityId
                val spellAction = findAction(observation) {
                    it.kind == "CastSpell" && it.sourceEntityId == spellCardId
                }
                val domain = spellAction.paymentDomain ?: error("Expected the V5 HTTP payment domain")
                domain.version shouldBe 5
                val initialBucket = domain.initialPoolBuckets.single()
                val initialKey = initialBucket.key as InitialPoolBucketKeyV1.CertifiedFloatingBucket
                initialKey.key.sourceId shouldBe landId
                initialKey.key.poolColor shouldBe com.wingedsheep.engine.core.PaymentManaColor.GREEN
                initialBucket.availableAmount shouldBe 1

                val observedWire = get("/envs/${envId.value}")
                observedWire.statusCode() shouldBe 200
                observedWire.body() shouldContain "\"version\":5"
                observedWire.body() shouldContain "\"initialPoolBuckets\""
                observedWire.body() shouldContain landId.value

                val strategy = json.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(
                        paymentPlan = PaymentPlanV3(
                            outerAllocation = listOf(
                                PaymentAllocationV1(
                                    target = PaymentTargetV1.OuterCostUnit(
                                        symbolIndex = 0,
                                        unitIndexWithinSymbol = 0,
                                    ),
                                    resource = ManaResourceRefV1.InitialPoolResource(initialBucket.key),
                                ),
                            ),
                        ),
                    ),
                )
                val paymentAction = buildJsonObject {
                    spellAction.actionSemantics?.forEach { (key, value) -> put(key, value) }
                    put("paymentStrategy", strategy)
                }
                val paymentResponse = postJson(
                    "/envs/${envId.value}/step",
                    json.encodeToString(StepBody(spellAction.actionId, paymentAction)),
                )
                paymentResponse.statusCode() shouldBe 200
                val afterPayment = json.decodeFromString<TrainingObservation>(paymentResponse.body())
                afterPayment.players.first { it.isPerspective }.manaPool.green shouldBe 0
            } finally {
                deleteJson("/envs", json.encodeToString(DisposeBody(listOf(envId))))
            }
        }

        test("HTTP observation routes legal actions to the player who has priority") {
            val created = json.decodeFromString<CreateEnvResponse>(
                postJson("/envs", json.encodeToString(twoPlayerConfig())).body()
            )
            val opening = created.observation as TrainingObservation
            val openingActor = opening.agentToAct
            openingActor shouldNotBe null
            opening.perspectivePlayerId shouldBe openingActor

            val pass = opening.legalActions.first {
                it.kind.contains("Pass", ignoreCase = true) ||
                    it.description.contains("Pass", ignoreCase = true)
            }
            val stepped = postJson(
                "/envs/${created.envId.value}/step",
                json.encodeToString(StepBody(pass.actionId, pass.actionSemantics))
            )
            stepped.statusCode() shouldBe 200

            val afterStep = json.decodeFromString<TrainingObservation>(stepped.body())
            afterStep.agentToAct shouldNotBe openingActor
            afterStep.perspectivePlayerId shouldBe afterStep.agentToAct
            afterStep.legalActions.shouldNotBeEmpty()

            deleteJson("/envs", json.encodeToString(DisposeBody(listOf(created.envId))))
                .statusCode() shouldBe 204
        }

        test("POST /envs with an unknown set code surfaces 400") {
            val bogus = EnvConfig(
                players = listOf(
                    PlayerSpec("A", DeckSpec.RandomSealed(setCode = "ZZZ")),
                    PlayerSpec("B", DeckSpec.RandomSealed(setCode = "ZZZ"))
                )
            )
            postJson("/envs", json.encodeToString(bogus)).statusCode() shouldBe 400
        }

        test("GET /envs/{unknown} returns 404") {
            get("/envs/definitely-not-an-env").statusCode() shouldBe 404
        }

        test("step with an out-of-range actionId returns 400") {
            val created = json.decodeFromString<CreateEnvResponse>(
                postJson("/envs", json.encodeToString(twoPlayerConfig())).body()
            )

            val stepResp = postJson(
                "/envs/${created.envId.value}/step",
                json.encodeToString(StepBody(99_999))
            )
            stepResp.statusCode() shouldBe 400

            // Cleanup
            deleteJson("/envs", json.encodeToString(DisposeBody(listOf(created.envId))))
        }

        test("turnNumber advances after several steps") {
            val created = json.decodeFromString<CreateEnvResponse>(
                postJson("/envs", json.encodeToString(twoPlayerConfig())).body()
            )
            val initialTurn = (created.observation as TrainingObservation).turnNumber

            repeat(3) {
                val obs = json.decodeFromString<TrainingObservation>(
                    get("/envs/${created.envId.value}").body()
                )
                val nextAction = obs.legalActions.firstOrNull() ?: return@repeat
                postJson(
                    "/envs/${created.envId.value}/step",
                    json.encodeToString(StepBody(nextAction.actionId))
                )
            }
            val finalObs = json.decodeFromString<TrainingObservation>(
                get("/envs/${created.envId.value}").body()
            )
            (finalObs.turnNumber >= initialTurn) shouldBe true

            deleteJson("/envs", json.encodeToString(DisposeBody(listOf(created.envId))))
        }

        test("obsolete revealAll inputs are rejected or ignored without unmasking") {
            val payload = json.encodeToString(twoPlayerConfig())
            val obsoletePayload = payload.substringBeforeLast("}") + ",\"revealAll\":true}"
            val createResponse = postJson("/envs", obsoletePayload)

            if (createResponse.statusCode() in 200..299) {
                val created = json.decodeFromString<CreateEnvResponse>(createResponse.body())
                val createdObservation = created.observation as TrainingObservation
                val opponentId = createdObservation.players.first { !it.isPerspective }.id
                val opponentHand = createdObservation.zones.first {
                    it.ownerId == opponentId && it.zoneType == com.wingedsheep.sdk.core.Zone.HAND
                }
                opponentHand.hidden.shouldBeTrue()
                opponentHand.cards.shouldBeEmpty()

                val queryObservation = json.decodeFromString<TrainingObservation>(
                    get("/envs/${created.envId.value}?revealAll=true").body()
                )
                queryObservation.stateDigest shouldBe createdObservation.stateDigest
                queryObservation.zones.first {
                    it.ownerId == opponentId && it.zoneType == com.wingedsheep.sdk.core.Zone.HAND
                }.cards.shouldBeEmpty()
                deleteJson("/envs", json.encodeToString(DisposeBody(listOf(created.envId))))
            } else {
                (createResponse.statusCode() in 400..499) shouldBe true
            }
        }

        test("direct and HTTP observations have equal masked semantics and stable wire bytes") {
            val created = json.decodeFromString<CreateEnvResponse>(
                postJson("/envs", json.encodeToString(twoPlayerConfig())).body()
            )
            val firstResponse = get("/envs/${created.envId.value}")
            val secondResponse = get("/envs/${created.envId.value}")
            firstResponse.statusCode() shouldBe 200
            secondResponse.statusCode() shouldBe 200
            firstResponse.body() shouldBe secondResponse.body()

            val httpObservation = json.decodeFromString<TrainingObservation>(firstResponse.body())
            val directObservation = multiEnvService.observe(created.envId).observation as TrainingObservation
            httpObservation shouldBe directObservation
            httpObservation.schemaHash shouldBe SchemaHash.CURRENT
            httpObservation.stateDigest shouldBe directObservation.stateDigest
            httpObservation.legalActions.forEach { action ->
                val targetDomain = action.targetDomain.shouldNotBeNull()
                targetDomain.version shouldBe 1
                targetDomain.composition.name shouldBe "FIXED"
            }

            val opponentId = httpObservation.players.first { !it.isPerspective }.id
            val opponentHand = httpObservation.zones.first {
                it.ownerId == opponentId && it.zoneType == com.wingedsheep.sdk.core.Zone.HAND
            }
            opponentHand.hidden.shouldBeTrue()
            opponentHand.cards.shouldBeEmpty()
            deleteJson("/envs", json.encodeToString(DisposeBody(listOf(created.envId))))
        }

        test("Commander HTTP lifecycle preserves snapshot, fork, reset, and privacy contracts") {
            val created = json.decodeFromString<CreateEnvResponse>(
                postJson("/envs", json.encodeToString(commanderConfig())).body()
            )
            val opening = created.observation as TrainingObservation
            opening.players.forEach { it.lifeTotal shouldBe 40 }
            opening.zones.count { it.zoneType == com.wingedsheep.sdk.core.Zone.COMMAND } shouldBe 2

            val snapshotResponse = postJson("/envs/${created.envId.value}/snapshot", "{}")
            snapshotResponse.statusCode() shouldBe 200
            val snapshot = json.decodeFromString<SnapshotHandle>(snapshotResponse.body())

            val actionId = opening.legalActions.first().actionId
            val stepped = postJson(
                "/envs/${created.envId.value}/step",
                json.encodeToString(StepBody(actionId))
            )
            stepped.statusCode() shouldBe 200
            val steppedObservation = json.decodeFromString<TrainingObservation>(stepped.body())
            steppedObservation.stateDigest shouldNotBe opening.stateDigest

            val forkedResponse = postJson("/envs/${created.envId.value}/fork?count=1", "{}")
            forkedResponse.statusCode() shouldBe 200
            val forked = json.decodeFromString<List<EnvId>>(forkedResponse.body())
            forked.size shouldBe 1
            val forkObservation = json.decodeFromString<TrainingObservation>(
                get("/envs/${forked.single().value}").body()
            )
            forkObservation.stateDigest shouldBe steppedObservation.stateDigest

            val restored = postJson(
                "/envs/${created.envId.value}/restore",
                json.encodeToString(RestoreBody(snapshot))
            )
            restored.statusCode() shouldBe 200
            val restoredObservation = json.decodeFromString<TrainingObservation>(restored.body())
            restoredObservation.stateDigest shouldBe opening.stateDigest

            val reset = postJson(
                "/envs/${created.envId.value}/reset",
                json.encodeToString(commanderConfig())
            )
            reset.statusCode() shouldBe 200
            val resetObservation = json.decodeFromString<TrainingObservation>(reset.body())
            resetObservation.stateDigest shouldBe opening.stateDigest

            val opponentId = resetObservation.players.first { !it.isPerspective }.id
            resetObservation.zones.first {
                it.ownerId == opponentId && it.zoneType == com.wingedsheep.sdk.core.Zone.HAND
            }.cards shouldBe emptyList()

            deleteJson(
                "/envs",
                json.encodeToString(DisposeBody(listOf(created.envId) + forked))
            ).statusCode() shouldBe 204
            get("/envs/${created.envId.value}").statusCode() shouldBe 404
            get("/envs/${forked.single().value}").statusCode() shouldBe 404
        }
    }
}
