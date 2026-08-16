package com.wingedsheep.gym.server.controller

import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.server.dto.CreateEnvResponse
import com.wingedsheep.gym.server.dto.DisposeBody
import com.wingedsheep.gym.server.dto.ResetEnvResponse
import com.wingedsheep.gym.server.dto.StepBody
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.PerspectiveMode
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Real-wire proof for the ArgentML Commander self-play contract. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArgentMlGymHttpContractTest : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: HttpClient = HttpClient.newBuilder().build()

    init {
        extension(SpringExtension())

        fun baseUrl() = "http://localhost:$port"

        fun postJson(path: String, body: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("${baseUrl()}$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        fun deleteJson(path: String, body: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("${baseUrl()}$path"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        test("Commander seed and actor perspective round-trip over HTTP") {
            val deck = DeckSpec.Explicit(
                mapOf(
                    "Mountain" to 17,
                    "Raging Goblin" to 3,
                )
            )
            val config = EnvConfig(
                players = listOf(
                    PlayerSpec("Alice", deck, commanderCardName = "Raging Goblin"),
                    PlayerSpec("Bob", deck, commanderCardName = "Raging Goblin"),
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                perspectiveMode = PerspectiveMode.AGENT_TO_ACT,
                format = Format.Commander(),
                seed = 424242L,
            )

            val createResponse = postJson("/envs", json.encodeToString(config))
            createResponse.statusCode() shouldBe 200
            val created = json.decodeFromString<CreateEnvResponse>(createResponse.body())
            created.seed shouldBe 424242L

            val opening = created.observation as TrainingObservation
            opening.terminated.shouldBeFalse()
            opening.perspectivePlayerId shouldBe opening.agentToAct
            opening.legalActions.shouldNotBeEmpty()
            opening.players.forEach { it.lifeTotal shouldBe 40 }

            val commandZones = opening.zones.filter { it.zoneType == Zone.COMMAND }
            commandZones shouldHaveSize 2
            commandZones.forEach { zone ->
                zone.hidden.shouldBeFalse()
                zone.cards shouldHaveSize 1
                zone.cards.single().name shouldBe "Raging Goblin"
            }

            val previousPerspective = opening.perspectivePlayerId
            val pass = opening.legalActions.first {
                it.kind.contains("Pass", ignoreCase = true) ||
                    it.description.contains("Pass", ignoreCase = true)
            }
            val stepResponse = postJson(
                "/envs/${created.envId.value}/step",
                json.encodeToString(StepBody(pass.actionId)),
            )
            stepResponse.statusCode() shouldBe 200

            val afterPass = json.decodeFromString<TrainingObservation>(stepResponse.body())
            afterPass.perspectivePlayerId shouldBe afterPass.agentToAct
            afterPass.perspectivePlayerId shouldNotBe previousPerspective
            afterPass.legalActions.shouldNotBeEmpty()

            val previousHand = afterPass.zones.first {
                it.ownerId == previousPerspective && it.zoneType == Zone.HAND
            }
            previousHand.hidden.shouldBeTrue()
            previousHand.cards.shouldBeEmpty()

            val resetConfig = config.copy(seed = 777_777L)
            val resetResponse = postJson(
                "/envs/${created.envId.value}/reset",
                json.encodeToString(resetConfig),
            )
            resetResponse.statusCode() shouldBe 200
            val reset = json.decodeFromString<ResetEnvResponse>(resetResponse.body())
            reset.seed shouldBe 777_777L
            val resetObservation = reset.observation as TrainingObservation
            resetObservation.perspectivePlayerId shouldBe resetObservation.agentToAct
            resetObservation.legalActions.shouldNotBeEmpty()

            deleteJson(
                "/envs",
                json.encodeToString(DisposeBody(listOf(created.envId))),
            ).statusCode() shouldBe 204
        }
    }
}
