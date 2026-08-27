package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.engine.legalactions.RulesAttackBandConstraints
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomain
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class AttackDeclarationDomainContractTest : FunSpec({

    fun registry() = com.wingedsheep.engine.registry.CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 8)),
            PlayerConfig("Bob", Deck.of("Mountain" to 8)),
        ),
        startingHandSize = 2,
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    test("requires explicit combat payload fields and publishes an attack domain") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(config())
        val player = environment.playerIds.first()
        val state = environment.state.copy(
            phase = Phase.COMBAT,
            step = Step.DECLARE_ATTACKERS,
            activePlayerId = player,
            priorityPlayerId = player,
        )
        val result = ObservationBuilder(cardRegistry = cardRegistry).build(
            state = state,
            perspectivePlayerId = player,
            legalActions = listOf(
                LegalAction(
                    action = DeclareAttackers(player, emptyMap()),
                    actionType = "DeclareAttackers",
                    description = "Declare attackers",
                    attackDeclarationDomain = RulesAttackDeclarationDomain(
                        attackerOrder = emptyList(),
                        defenderOrder = emptyList(),
                        attackerToDefenders = emptyMap(),
                        mandatoryAttackers = emptyList(),
                        canDeclareZeroAttackers = true,
                        maxAttackers = null,
                        coAttackerRequirements = emptyMap(),
                        bandConstraints = RulesAttackBandConstraints(
                            bandingAttackersByDefender = emptyMap(),
                            nonBandingAttackersByDefender = emptyMap(),
                        ),
                    ),
                    attackDeclarationDomainSupport = AttackDeclarationDomainSupport.SUPPORTED,
                )
            ),
        )
        val view = (result.observation as TrainingObservation).legalActions.single()
        view.attackDeclarationDomain shouldNotBe null
        view.requiredPayloadFields shouldBe listOf("attackers", "bands")

        val actionSerialization = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
        }
        val json = actionSerialization
            .encodeToJsonElement(LegalActionView.serializer(), view)
            .jsonObject
        json["attackDeclarationDomain"] shouldNotBe null
    }
})
