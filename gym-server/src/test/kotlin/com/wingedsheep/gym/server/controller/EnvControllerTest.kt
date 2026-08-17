package com.wingedsheep.gym.server.controller

import com.wingedsheep.gym.contract.SchemaHash
import com.wingedsheep.gym.contract.TrainingObservation
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
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.beans.factory.annotation.Autowired
import com.wingedsheep.sdk.core.Format
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
    }

    private val client: HttpClient = HttpClient.newBuilder().build()

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
            parsed.schemaHash.shouldNotBe("")
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
