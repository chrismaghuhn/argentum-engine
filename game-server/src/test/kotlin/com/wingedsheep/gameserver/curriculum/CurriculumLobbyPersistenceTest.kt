package com.wingedsheep.gameserver.curriculum

import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.lobby.TournamentLobby
import com.wingedsheep.gameserver.persistence.restoreTournamentLobby
import com.wingedsheep.gameserver.persistence.toPersistent
import com.wingedsheep.gameserver.persistence.MatchResultRepository
import com.wingedsheep.gameserver.persistence.TournamentRepository
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.gameserver.stats.JdbcMatchResultSink
import com.wingedsheep.gameserver.stats.JdbcTournamentResultSink
import com.wingedsheep.gameserver.stats.RecordedMatch
import com.wingedsheep.gameserver.stats.RecordedParticipant
import com.wingedsheep.gameserver.stats.RecordedTournament
import com.wingedsheep.gameserver.stats.RecordedTournamentParticipant
import io.mockk.mockk
import io.mockk.verify
import com.wingedsheep.sdk.core.DeckFormat
import com.wingedsheep.sdk.core.GameRules
import com.wingedsheep.sdk.model.EntityId
import java.time.Instant
import java.util.UUID
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CurriculumLobbyPersistenceTest : FunSpec({

    test("locked curriculum provenance, commander seats, and runtime guards survive persistence") {
        val provenance = CurriculumMatchProvenanceV1(
            presetIdentity = CurriculumAiTournamentPreset.AKIRI_CHEVILL.identity,
            sources = listOf(
                CurriculumSourceProvenanceV1("akiri.txt", "akiri-digest", "Akiri", 100),
                CurriculumSourceProvenanceV1("chevill.txt", "chevill-digest", "Chevill", 100),
            ),
        )
        val lobby = TournamentLobby(
            setCodes = emptyList(),
            setNames = emptyList(),
            boosterGenerator = BoosterGenerator(emptyMap()),
            format = TournamentFormat.PREMADE_DECKS,
            maxPlayers = 2,
            deckFormat = DeckFormat.COMMANDER,
            rules = GameRules.COMMANDER,
            immutableFixedDeckSource = true,
            curriculumProvenance = provenance,
            engineAiOnly = true,
            recordDurableStats = false,
        )
        val akiri = PlayerIdentity(playerId = EntityId("akiri"), playerName = "Akiri")
        val chevill = PlayerIdentity(playerId = EntityId("chevill"), playerName = "Chevill")
        lobby.addPlayer(akiri)
        lobby.addPlayer(chevill)
        lobby.players[akiri.playerId]!!.commander = "Akiri, Fearless Voyager"
        lobby.players[chevill.playerId]!!.commander = "Chevill, Bane of Monsters"

        val restored = restoreTournamentLobby(
            lobby.toPersistent(),
            CardRegistry(),
            BoosterGenerator(emptyMap()),
        ).first

        restored.immutableFixedDeckSource shouldBe true
        restored.engineAiOnly shouldBe true
        restored.recordDurableStats shouldBe false
        restored.curriculumProvenance shouldBe provenance
        restored.players[EntityId("akiri")]?.commander shouldBe "Akiri, Fearless Voyager"
        restored.players[EntityId("chevill")]?.commander shouldBe "Chevill, Bane of Monsters"
    }

    test("ordinary tournament lobbies retain durable stats by default") {
        TournamentLobby(
            setCodes = listOf("ECL"),
            setNames = listOf("Lorwyn Eclipsed"),
            boosterGenerator = BoosterGenerator(emptyMap()),
        ).recordDurableStats shouldBe true
    }

    test("recovered Research Arena policy suppresses completion writes in both durable sinks") {
        val lobby = TournamentLobby(
            setCodes = emptyList(),
            setNames = emptyList(),
            boosterGenerator = BoosterGenerator(emptyMap()),
            format = TournamentFormat.PREMADE_DECKS,
            maxPlayers = 2,
            deckFormat = DeckFormat.COMMANDER,
            rules = GameRules.COMMANDER,
            immutableFixedDeckSource = true,
            recordDurableStats = false,
        )
        val restored = restoreTournamentLobby(
            lobby.toPersistent(),
            CardRegistry(),
            BoosterGenerator(emptyMap()),
        ).first
        restored.recordDurableStats shouldBe false

        val matchRepository = mockk<MatchResultRepository>(relaxed = true)
        JdbcMatchResultSink(matchRepository).record(
            RecordedMatch(
                gameId = "recovered-game",
                recordDurableStats = restored.recordDurableStats,
                format = "COMMANDER",
                tournamentName = "Research Arena",
                lobbyId = restored.lobbyId,
                gameMode = "TOURNAMENT",
                frameCount = 20,
                turnCount = 6,
                startedAt = Instant.now(),
                endedAt = Instant.now(),
                participants = listOf(
                    RecordedParticipant(
                        userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                        playerName = "Akiri",
                        won = true,
                    ),
                    RecordedParticipant(userId = null, playerName = "Chevill", won = false, isAi = true),
                ),
            ),
        )
        verify(exactly = 0) { matchRepository.save(any()) }

        val tournamentRepository = mockk<TournamentRepository>(relaxed = true)
        JdbcTournamentResultSink(tournamentRepository).recordCompleted(
            RecordedTournament(
                lobbyId = restored.lobbyId,
                recordDurableStats = restored.recordDurableStats,
                name = "Research Arena",
                format = "PREMADE_DECKS",
                gameMode = "TOURNAMENT",
                setCodes = "",
                playerCount = 2,
                rounds = 1,
                gamesPerMatch = 1,
                winnerName = "Akiri",
                startedAt = Instant.now(),
                endedAt = Instant.now(),
                participants = listOf(
                    RecordedTournamentParticipant(
                        userId = UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                        playerName = "Akiri",
                        isAi = false,
                        placement = 1,
                        wins = 1,
                        losses = 0,
                        draws = 0,
                    ),
                ),
            ),
        )
        verify(exactly = 0) { tournamentRepository.save(any()) }
    }
})
