package com.wingedsheep.gameserver

import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.repository.LobbyRepository
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.time.Duration.Companion.seconds

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "game.dev-endpoints.enabled=false",
        "game.ai.enabled=true",
    ],
)
class ArenaHuman01DevEndpointDisabledTest : GameServerTestBase() {

    @Autowired
    private lateinit var lobbyRepository: LobbyRepository

    init {
        test("rejects the human curriculum launch before creating server resources") {
            val client = createClient()
            client.connectAs("Arena Dev Disabled")
            client.send(ClientMessage.StartCurriculumHumanVsEngineAi)

            eventually(10.seconds) {
                client.latestError()?.message shouldBe "Research Arena dev endpoint is not enabled on this server."
            }
            lobbyRepository.findAllLobbies().none { lobby ->
                lobby.players.values.any { it.identity.playerName == "Arena Dev Disabled" }
            } shouldBe true
        }
    }
}
