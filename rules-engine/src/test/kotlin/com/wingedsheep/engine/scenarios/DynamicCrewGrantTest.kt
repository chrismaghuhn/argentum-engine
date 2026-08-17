package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.event.GrantedKeywordAbility
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dom.cards.Weatherlight
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.GrantKeywordAbility
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.engineSerializersModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

private val CrewGrantAbility = GrantKeywordAbility(
    ability = KeywordAbility.crew(1),
    filter = GroupFilter(
        GameObjectFilter.Artifact
            .withSubtype(Subtype.VEHICLE)
            .youControl()
    )
)

private val CrewGrantArtifact = card("Crew Grant Artifact") {
    manaCost = "{2}"
    typeLine = "Artifact"
    staticAbility {
        ability = CrewGrantAbility
    }
}

/** Characterization for a parametrized Crew ability supplied through the runtime grant channel. */
class DynamicCrewGrantTest : FunSpec({

    fun setup(
        includeRuntimeGrant: Boolean = true,
        includePrintedStaticGrant: Boolean = false,
        includeRuntimeStaticGrant: Boolean = false
    ): Triple<GameTestDriver, com.wingedsheep.sdk.model.EntityId, com.wingedsheep.sdk.model.EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.registerCards(listOf(Weatherlight, CrewGrantArtifact))
        driver.initMirrorMatch(
            deck = Deck.of("Forest" to 40),
            skipMulligans = true
        )
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        val player = driver.activePlayer!!
        val vehicle = driver.putPermanentOnBattlefield(player, "Weatherlight")
        val crewer = driver.putCreatureOnBattlefield(player, "Grizzly Bears")

        if (includePrintedStaticGrant) {
            driver.putPermanentOnBattlefield(player, CrewGrantArtifact.name)
        }

        if (includeRuntimeGrant) {
            // This is the existing durable carrier for a parametrized keyword grant. The production
            // path under test must resolve it to the same entity-level contract as a printed grant.
            val grant = GrantedKeywordAbility(
                entityId = vehicle,
                ability = KeywordAbility.crew(1),
                duration = Duration.Permanent
            )
            driver.replaceState(
                driver.state.copy(
                    grantedKeywordAbilities = driver.state.grantedKeywordAbilities + grant
                )
            )
        }
        if (includeRuntimeStaticGrant) {
            val grant = GrantedStaticAbility(
                entityId = vehicle,
                ability = CrewGrantAbility,
                duration = Duration.Permanent
            )
            driver.replaceState(
                driver.state.copy(
                    grantedStaticAbilities = driver.state.grantedStaticAbilities + grant
                )
            )
        }
        return Triple(driver, vehicle, crewer)
    }

    test("enumerates a runtime-granted Crew value alongside the printed Crew value") {
        val (driver, vehicle, crewer) = setup()
        val player = driver.activePlayer!!

        val crewActions = driver.legalActions(player)
            .filter { it.action is CrewVehicle && it.action.vehicleId == vehicle }

        crewActions.map { it.tapForPowerRequired }.toSet() shouldBe setOf(1, 3)
        val keys = crewActions.map {
            (it.action as CrewVehicle).crewAbilityKey ?: error("Crew action lost its identity")
        }
        keys shouldBe keys.sorted()
        crewActions.single { it.tapForPowerRequired == 1 }
            .tapForPowerCreatures!!
            .map { it.entityId } shouldContain crewer
    }

    test("validates and executes a selected creature against the granted Crew value") {
        val (driver, vehicle, crewer) = setup()
        val player = driver.activePlayer!!

        val result = driver.submit(CrewVehicle(player, vehicle, listOf(crewer)))

        result.isSuccess shouldBe true
        driver.isTapped(crewer) shouldBe true
        driver.stackSize shouldBe 1
    }

    test("rejects a stale serialized Crew identity") {
        val (driver, vehicle, crewer) = setup()
        val player = driver.activePlayer!!

        val result = driver.submit(
            CrewVehicle(
                playerId = player,
                vehicleId = vehicle,
                crewCreatures = listOf(crewer),
                crewAbilityKey = "stale-crew-instance"
            )
        )

        result.isSuccess shouldBe false
        result.error shouldBe "Crew ability is stale or ambiguous"
    }

    test("rejects an identity-less action when multiple Crew values are payable") {
        val (driver, vehicle, _) = setup()
        val player = driver.activePlayer!!
        val largeCrew = driver.putCreatureOnBattlefield(player, "Force of Nature")

        val result = driver.submit(CrewVehicle(player, vehicle, listOf(largeCrew)))

        result.isSuccess shouldBe false
        result.error shouldBe "Crew ability identity is required"
    }

    test("resolves a printed static parameterized grant through the same Crew boundary") {
        val (driver, vehicle, crewer) = setup(
            includeRuntimeGrant = false,
            includePrintedStaticGrant = true
        )
        val player = driver.activePlayer!!

        val crewActions = driver.legalActions(player)
            .filter { it.action is CrewVehicle && it.action.vehicleId == vehicle }

        crewActions.map { it.tapForPowerRequired }.toSet() shouldBe setOf(1, 3)
        val keys = crewActions.map {
            (it.action as CrewVehicle).crewAbilityKey ?: error("Crew action lost its identity")
        }
        keys shouldBe keys.sorted()
        crewActions.map { (it.action as CrewVehicle).crewAbilityKey }
            .toSet()
            .size shouldBe crewActions.size

        val granted = crewActions.single { it.tapForPowerRequired == 1 }
        (granted.action as CrewVehicle).crewAbilityKey shouldBe
            "static:${driver.findPermanent(player, CrewGrantArtifact.name)}:0:numeric:CREW:1:false"
        granted.tapForPowerCreatures!!.map { it.entityId } shouldContain crewer
    }

    test("resolves a runtime static parameterized grant through the same Crew boundary") {
        val (driver, vehicle, crewer) = setup(
            includeRuntimeGrant = false,
            includeRuntimeStaticGrant = true
        )
        val player = driver.activePlayer!!

        val crewActions = driver.legalActions(player)
            .filter { it.action is CrewVehicle && it.action.vehicleId == vehicle }

        crewActions.map { it.tapForPowerRequired }.toSet() shouldBe setOf(1, 3)
        val granted = crewActions.single { it.tapForPowerRequired == 1 }
        (granted.action as CrewVehicle).crewAbilityKey shouldBe
            "runtime-static:$vehicle:0:numeric:CREW:1:false"
        granted.tapForPowerCreatures!!.map { it.entityId } shouldContain crewer
    }

    test("serialized Crew identity survives a replay-shaped action round trip") {
        val (driver, vehicle, _) = setup()
        val player = driver.activePlayer!!
        val action = driver.legalActions(player)
            .map { it.action }
            .filterIsInstance<CrewVehicle>()
            .single { it.crewAbilityKey!!.contains(":1:") }

        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
        }
        val decoded = json.decodeFromString(
            GameAction.serializer(),
            json.encodeToString(GameAction.serializer(), action)
        )

        decoded shouldBe action
        (decoded as CrewVehicle).vehicleId shouldBe vehicle
    }
})
