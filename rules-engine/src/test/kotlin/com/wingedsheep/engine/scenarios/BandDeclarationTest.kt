package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.mechanics.combat.CombatManager
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.GameRng
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tests for declaring attacking bands (CR 702.22). A band groups one or more attacking
 * creatures with banding plus up to one without; all members attack the same defender and
 * are stamped with a shared [AttackingComponent.bandId].
 */
class BandDeclarationTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        return driver
    }

    test("a banding creature and a non-banding creature can be declared as one band") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
        val active = driver.activePlayer!!
        val opponent = driver.getOpponent(active)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val scout = driver.putCreatureOnBattlefield(active, "Banding Scout")
        val courser = driver.putCreatureOnBattlefield(active, "Centaur Courser")
        driver.removeSummoningSickness(scout)
        driver.removeSummoningSickness(courser)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackingBand(active, listOf(scout, courser), opponent).isSuccess shouldBe true

        // Both attackers carry the same, non-null band id (CR 702.22).
        val scoutBand = driver.state.getEntity(scout)?.get<AttackingComponent>()?.bandId
        val courserBand = driver.state.getEntity(courser)?.get<AttackingComponent>()?.bandId
        scoutBand.shouldNotBeNull()
        scoutBand shouldBe "combat-band-0"
        courserBand shouldBe scoutBand
    }

    test("a lone attacker has no band id") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
        val active = driver.activePlayer!!
        val opponent = driver.getOpponent(active)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val scout = driver.putCreatureOnBattlefield(active, "Banding Scout")
        driver.removeSummoningSickness(scout)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(active, listOf(scout), opponent).isSuccess shouldBe true

        driver.state.getEntity(scout)?.get<AttackingComponent>()?.bandId shouldBe null
    }

    test("a band must contain at least two creatures") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
        val active = driver.activePlayer!!
        val opponent = driver.getOpponent(active)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val scout = driver.putCreatureOnBattlefield(active, "Banding Scout")
        driver.removeSummoningSickness(scout)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        val result = driver.submit(
            DeclareAttackers(active, mapOf(scout to opponent), bands = listOf(setOf(scout)))
        )
        result.isSuccess shouldBe false
        result.error shouldNotBe null
    }

    test("a band may contain at most one creature without banding") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
        val active = driver.activePlayer!!
        val opponent = driver.getOpponent(active)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val courserA = driver.putCreatureOnBattlefield(active, "Centaur Courser")
        val courserB = driver.putCreatureOnBattlefield(active, "Centaur Courser")
        driver.removeSummoningSickness(courserA)
        driver.removeSummoningSickness(courserB)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        val result = driver.declareAttackingBand(active, listOf(courserA, courserB), opponent)
        result.isSuccess shouldBe false
        result.error shouldNotBe null
    }

    test("equivalent seeded band declarations receive the same semantic identity") {
        fun executeEquivalentDeclaration(): String {
            val driver = createDriver()
            driver.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
            val active = driver.activePlayer!!
            val opponent = driver.getOpponent(active)

            driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
            val scout = driver.putCreatureOnBattlefield(active, "Banding Scout")
            val courser = driver.putCreatureOnBattlefield(active, "Centaur Courser")
            driver.removeSummoningSickness(scout)
            driver.removeSummoningSickness(courser)

            // The initialized fixtures intentionally have different transient EntityIds. Make the
            // declaration boundary use the same explicit seed before submitting the same choice.
            driver.replaceState(driver.state.copy(rng = GameRng(7L)))
            driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
            driver.declareAttackingBand(active, listOf(scout, courser), opponent).isSuccess shouldBe true

            return checkNotNull(driver.state.getEntity(scout)?.get<AttackingComponent>()?.bandId)
        }

        executeEquivalentDeclaration() shouldBe executeEquivalentDeclaration()
    }

    test("two disjoint bands receive deterministic distinct ordinals") {
        val fixture = prepareTwoBandFixture()
        declareTwoBands(fixture)

        bandIdsByRank(fixture).values.toSet() shouldBe setOf("combat-band-0", "combat-band-1")
        fixture.attackers.map { attacker ->
            fixture.driver.state.getEntity(attacker)?.get<AttackingComponent>()?.bandId
        }.distinct().size shouldBe 2
    }

    test("reversed band and member input order has the same semantic result") {
        val forward = prepareTwoBandFixture()
        val reversed = cloneFixture(forward)
        val (firstBanding, firstNonBanding, secondBanding, secondNonBanding) = forward.attackers
        val forwardAction = DeclareAttackers(
            playerId = forward.active,
            attackers = linkedMapOf(
                firstBanding to forward.opponent,
                firstNonBanding to forward.opponent,
                secondBanding to forward.opponent,
                secondNonBanding to forward.opponent,
            ),
            bands = listOf(
                linkedSetOf(firstBanding, firstNonBanding),
                linkedSetOf(secondBanding, secondNonBanding),
            ),
        )
        val reversedAction = DeclareAttackers(
            playerId = reversed.active,
            attackers = linkedMapOf(
                secondNonBanding to reversed.opponent,
                secondBanding to reversed.opponent,
                firstNonBanding to reversed.opponent,
                firstBanding to reversed.opponent,
            ),
            bands = listOf(
                linkedSetOf(secondNonBanding, secondBanding),
                linkedSetOf(firstNonBanding, firstBanding),
            ),
        )

        forward.driver.submit(forwardAction).isSuccess shouldBe true
        reversed.driver.submit(reversedAction).isSuccess shouldBe true

        forward.driver.state shouldBe reversed.driver.state
        bandIdsByRank(forward) shouldBe bandIdsByRank(reversed)
    }

    test("different band membership produces a different semantic state") {
        val first = prepareTwoBandFixture()
        val second = cloneFixture(first)
        val (firstBanding, firstNonBanding, secondBanding, secondNonBanding) = first.attackers

        declareTwoBands(first)
        second.driver.submit(
            DeclareAttackers(
                playerId = second.active,
                attackers = second.attackers.associateWith { second.opponent },
                bands = listOf(
                    setOf(firstBanding, secondNonBanding),
                    setOf(secondBanding, firstNonBanding),
                ),
            )
        ).isSuccess shouldBe true

        bandIdsByRank(first) shouldNotBe bandIdsByRank(second)
    }

    test("transient entity IDs do not affect canonical band ordinals") {
        val base = prepareTwoBandFixture()
        val idsByRank = base.attackers.sortedBy { base.driver.state.objectIdentityStamps.getValue(it) }
        val firstNames = listOf("z-rank-0", "z-rank-1", "a-rank-2", "a-rank-3")
        val secondNames = listOf("a-rank-0", "a-rank-1", "z-rank-2", "z-rank-3")
        val first = remapAttackers(base, idsByRank.zip(firstNames).toMap())
        val second = remapAttackers(base, idsByRank.zip(secondNames).toMap())

        declareTwoBands(first)
        declareTwoBands(second)

        bandIdsByRank(first) shouldBe bandIdsByRank(second)
        bandIdsByRank(first).values.toSet() shouldBe setOf("combat-band-0", "combat-band-1")
    }

    test("forks before declaration produce identical band state") {
        val first = prepareBandFixture()
        val second = cloneFixture(first)
        val action = DeclareAttackers(
            playerId = first.active,
            attackers = mapOf(
                first.attackers[0] to first.opponent,
                first.attackers[1] to first.opponent,
            ),
            bands = listOf(setOf(first.attackers[0], first.attackers[1])),
        )

        first.driver.submit(action).isSuccess shouldBe true
        second.driver.submit(action).isSuccess shouldBe true

        first.driver.state shouldBe second.driver.state
    }

    test("snapshot restore before declaration preserves band determinism") {
        val fixture = prepareBandFixture()
        val json = Json {
            serializersModule = engineSerializersModule
            encodeDefaults = true
            allowStructuredMapKeys = true
        }
        val restoredState = json.decodeFromString<GameState>(
            json.encodeToString(GameState.serializer(), fixture.driver.state)
        )
        val restored = cloneFixture(fixture, restoredState)
        val action = DeclareAttackers(
            playerId = fixture.active,
            attackers = fixture.attackers.associateWith { fixture.opponent },
            bands = listOf(fixture.attackers.toSet()),
        )

        fixture.driver.submit(action).isSuccess shouldBe true
        restored.driver.submit(action).isSuccess shouldBe true

        fixture.driver.state shouldBe restored.driver.state
        fixture.driver.state.getEntity(fixture.attackers[0])
            ?.get<AttackingComponent>()?.bandId shouldBe "combat-band-0"

        val serializedDeclaredState = json.decodeFromString<GameState>(
            json.encodeToString(GameState.serializer(), fixture.driver.state)
        )
        serializedDeclaredState shouldBe fixture.driver.state
        serializedDeclaredState.getEntity(fixture.attackers[1])
            ?.get<AttackingComponent>()?.bandId shouldBe "combat-band-0"
    }

    test("malformed overlapping bands remain rejected atomically") {
        val fixture = prepareTwoBandFixture()
        val before = fixture.driver.state
        val result = fixture.driver.submit(
            DeclareAttackers(
                playerId = fixture.active,
                attackers = fixture.attackers.associateWith { fixture.opponent },
                bands = listOf(
                    setOf(fixture.attackers[0], fixture.attackers[1]),
                    setOf(fixture.attackers[1], fixture.attackers[2]),
                ),
            )
        )

        result.isSuccess shouldBe false
        fixture.driver.state shouldBe before
    }

    test("end combat removes every band identity with attacking state") {
        val fixture = prepareBandFixture()
        fixture.driver.declareAttackingBand(
            fixture.active,
            fixture.attackers,
            fixture.opponent,
        ).isSuccess shouldBe true

        val combat = CombatManager(
            fixture.driver.cardRegistry,
            ManaAbilitySideEffectExecutor.noOp(fixture.driver.cardRegistry),
        )
        val ended = combat.endCombat(fixture.driver.state)

        ended.isSuccess shouldBe true
        fixture.attackers.forEach { attacker ->
            ended.newState.getEntity(attacker)?.get<AttackingComponent>() shouldBe null
        }
    }
})

private data class BandFixture(
    val driver: GameTestDriver,
    val active: com.wingedsheep.sdk.model.EntityId,
    val opponent: com.wingedsheep.sdk.model.EntityId,
    val attackers: List<com.wingedsheep.sdk.model.EntityId>,
)

private fun prepareBandFixture(): BandFixture {
    val driver = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
    }
    val active = driver.activePlayer!!
    val opponent = driver.getOpponent(active)
    driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
    val scout = driver.putCreatureOnBattlefield(active, "Banding Scout")
    val courser = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    listOf(scout, courser).forEach(driver::removeSummoningSickness)
    driver.replaceState(driver.state.copy(rng = GameRng(7L)))
    driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
    return BandFixture(driver, active, opponent, listOf(scout, courser))
}

private fun prepareTwoBandFixture(): BandFixture {
    val driver = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.initMirrorMatch(deck = Deck.of("Plains" to 20, "Forest" to 20), startingLife = 20)
    }
    val active = driver.activePlayer!!
    val opponent = driver.getOpponent(active)
    driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
    val attackers = listOf(
        driver.putCreatureOnBattlefield(active, "Banding Scout"),
        driver.putCreatureOnBattlefield(active, "Centaur Courser"),
        driver.putCreatureOnBattlefield(active, "Banding Scout"),
        driver.putCreatureOnBattlefield(active, "Centaur Courser"),
    )
    attackers.forEach(driver::removeSummoningSickness)
    driver.replaceState(driver.state.copy(rng = GameRng(7L)))
    driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
    return BandFixture(driver, active, opponent, attackers)
}

private fun cloneFixture(source: BandFixture, state: GameState = source.driver.state): BandFixture {
    val driver = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.replaceState(state)
    }
    return BandFixture(driver, source.active, source.opponent, source.attackers)
}

private fun remapAttackers(
    source: BandFixture,
    replacements: Map<com.wingedsheep.sdk.model.EntityId, String>,
): BandFixture {
    val state = source.driver.state
    val idMap = replacements.mapValues { (_, value) -> com.wingedsheep.sdk.model.EntityId(value) }
    fun remap(id: com.wingedsheep.sdk.model.EntityId): com.wingedsheep.sdk.model.EntityId = idMap[id] ?: id
    val remappedState = state.copy(
        entities = state.entities.entries.associate { (id, entity) -> remap(id) to entity },
        zones = state.zones.mapValues { (_, ids) -> ids.map(::remap) },
        objectIdentityStamps = state.objectIdentityStamps.entries.associate { (id, stamp) -> remap(id) to stamp },
    )
    return cloneFixture(source, remappedState).copy(attackers = source.attackers.map(::remap))
}

private fun declareTwoBands(fixture: BandFixture) {
    val (firstBanding, firstNonBanding, secondBanding, secondNonBanding) = fixture.attackers
    fixture.driver.submit(
        DeclareAttackers(
            playerId = fixture.active,
            attackers = fixture.attackers.associateWith { fixture.opponent },
            bands = listOf(
                setOf(firstBanding, firstNonBanding),
                setOf(secondBanding, secondNonBanding),
            ),
        )
    ).isSuccess shouldBe true
}

private fun bandIdsByRank(fixture: BandFixture): Map<Long, String?> = fixture.attackers
    .associate { attacker ->
        fixture.driver.state.objectIdentityStamps.getValue(attacker) to
            fixture.driver.state.getEntity(attacker)?.get<AttackingComponent>()?.bandId
    }
