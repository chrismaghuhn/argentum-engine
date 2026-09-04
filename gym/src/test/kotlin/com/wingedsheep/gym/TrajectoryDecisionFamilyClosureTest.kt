package com.wingedsheep.gym

import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.gym.contract.ChosenSemanticResponseV1
import com.wingedsheep.gym.contract.CompleteLegalDomainKind
import com.wingedsheep.gym.contract.CompleteLegalDomainV1
import com.wingedsheep.gym.contract.ManaSourcesDomain
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PendingDecisionKind
import com.wingedsheep.gym.contract.SemanticDecisionKindV1
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * A8 RED characterization for the legacy pending-mana branch.
 *
 * Mentor of the Meek and Fervent Champion are both in the locked Akiri deck; Battlefield Forge
 * is its locked multi-colour mana source.  The scenario uses the existing Rules test fixture only
 * to reach the real pending-decision boundary, then observes that boundary through GameGymEnv.
 */
class TrajectoryDecisionFamilyClosureTest : ScenarioTestBase() {

    init {
        test("durable semantic vocabulary names every current pending family plus priority") {
            PendingDecisionKind.entries.map(PendingDecisionKind::name) shouldBe
                SemanticDecisionKindV1.entries
                    .filterNot { it == SemanticDecisionKindV1.PRIORITY }
                    .map(SemanticDecisionKindV1::name)
        }

        test("locked Mentor payment reaches a pending mana family without an explicit production allocation") {
            val lockedAkiriCards = lockedDeckCards("akiri-v0.1.txt")
            setOf("Mentor of the Meek", "Fervent Champion", "Battlefield Forge")
                .all(lockedAkiriCards::contains) shouldBe true

            val game = scenario()
                .withPlayers()
                .withFormat(Format.Commander())
                .withCardOnBattlefield(1, "Mentor of the Meek")
                .withCardOnBattlefield(1, "Battlefield Forge")
                .withCardInHand(1, "Fervent Champion")
                .withCardInLibrary(1, "Plains")
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .withRngSeed(8L)
                .build()
            game.state = game.state.updateEntity(game.player1Id) { container ->
                container.with(ManaPoolComponent(red = 1))
            }

            game.castSpell(1, "Fervent Champion").error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
            game.answerYesNo(true).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<SelectManaSourcesDecision>()

            val environment = GameEnvironment.create(
                cardRegistry = cardRegistry,
                executionMode = GameEnvironmentMode.TRUSTED,
            )
            environment.restore(
                state = game.state,
                playerIds = listOf(game.player1Id, game.player2Id),
            )
            val publicObservation = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ).observe().observation.shouldBeInstanceOf<TrainingObservation>()

            val pending = publicObservation.pendingDecision
                ?: error("Mentor payment did not reach the public pending-decision boundary")
            pending.kind shouldBe PendingDecisionKind.SELECT_MANA_SOURCES
            pending.requiresStructuredResponse shouldBe true
            val sourceDomain = pending.structuredDomain.shouldBeInstanceOf<ManaSourcesDomain>()
            val battlefieldForge = sourceDomain.availableSources.single { it.name == "Battlefield Forge" }
            battlefieldForge.producesColors shouldBe setOf(Color.RED, Color.WHITE)

            val completeDomain = CompleteLegalDomainV1.from(publicObservation)
            completeDomain.kind shouldBe CompleteLegalDomainKind.STRUCTURED_DECISION
            completeDomain.structuredDomain shouldBe sourceDomain

            val chosen = ChosenSemanticResponseV1.from(
                domain = completeDomain,
                response = ManaSourcesSelectedResponse(
                    decisionId = pending.decisionId ?: error("Missing live pending-decision routing ID"),
                    selectedSources = listOf(battlefieldForge.entityId),
                    autoPay = false,
                ),
            )

            // A3 removes the forbidden AutoPay transport field. The only source/production
            // selector that remains is selectedSources; waterbendPermanents and declined cannot
            // name a Battlefield Forge production, and there is no V5-style allocation witness.
            chosen.response.keys shouldBe setOf(
                "type",
                "selectedSources",
                "waterbendPermanents",
                "declined",
            )
        }
    }

    private fun lockedDeckCards(fileName: String): Set<String> {
        val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
        return Files.readAllLines(repositoryRoot.resolve("docs/ml/curriculum").resolve(fileName))
            .asSequence()
            .filter { it.matches(Regex("^\\d{3}\\t.*")) }
            .map { it.substringAfterLast('\t') }
            .toSet()
    }
}
