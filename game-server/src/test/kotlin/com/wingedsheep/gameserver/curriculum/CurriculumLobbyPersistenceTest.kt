package com.wingedsheep.gameserver.curriculum

import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gameserver.lobby.TournamentFormat
import com.wingedsheep.gameserver.lobby.TournamentLobby
import com.wingedsheep.gameserver.persistence.restoreTournamentLobby
import com.wingedsheep.gameserver.persistence.toPersistent
import com.wingedsheep.gameserver.session.PlayerIdentity
import com.wingedsheep.sdk.core.DeckFormat
import com.wingedsheep.sdk.core.GameRules
import com.wingedsheep.sdk.model.EntityId
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
        restored.curriculumProvenance shouldBe provenance
        restored.players[EntityId("akiri")]?.commander shouldBe "Akiri, Fearless Voyager"
        restored.players[EntityId("chevill")]?.commander shouldBe "Chevill, Bane of Monsters"
    }
})
