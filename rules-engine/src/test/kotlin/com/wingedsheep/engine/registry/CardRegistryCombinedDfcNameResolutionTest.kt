package com.wingedsheep.engine.registry

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.model.CardLayout
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Focused characterization for canonical external names of physical double-faced cards.
 *
 * The catalog is the same production corpus that backs the game-server registry. These tests
 * exercise the public boundaries that blocked Environment V1 and the identity invariants that
 * keep the alias from changing card pools or other multi-face layouts.
 */
class CardRegistryCombinedDfcNameResolutionTest : FunSpec({

    val registry = catalogRegistry()
    val outlandCombined = "Outland Liberator // Frenzied Trapbreaker"
    val garrukCombined = "Garruk Relentless // Garruk, the Veil-Cursed"

    test("exact combined DFC names resolve to their existing front definitions") {
        val outland = runCatching { registry.requireCard(outlandCombined) }
        outland.exceptionOrNull()?.let { failure ->
            println("Outland failure: ${failure::class.qualifiedName} code=${diagnosticCode(failure)}")
        }
        outland.getOrNull()?.name shouldBe "Outland Liberator"
        (outland.getOrNull() === registry.requireCard("Outland Liberator")) shouldBe true

        val garruk = runCatching { registry.requireCard(garrukCombined) }
        garruk.exceptionOrNull()?.let { failure ->
            println("Garruk failure: ${failure::class.qualifiedName} code=${diagnosticCode(failure)}")
        }
        garruk.getOrNull()?.name shouldBe "Garruk Relentless"
        (garruk.getOrNull() === registry.requireCard("Garruk Relentless")) shouldBe true
    }

    test("GameInitializer accepts a real deck entry with a combined DFC name") {
        val initialization = runCatching {
            GameInitializer(registry).initializeGame(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Player 1", Deck.of(outlandCombined to 1)),
                        PlayerConfig("Player 2", Deck.of("Forest" to 1)),
                    ),
                    startingHandSize = 0,
                    skipMulligans = true,
                    seed = 73L,
                ),
            )
        }

        initialization.exceptionOrNull()?.let { exception ->
            println("GameInitializer failure: ${exception::class.qualifiedName} code=${diagnosticCode(exception)}")
        }
        initialization.exceptionOrNull() shouldBe null
        initialization.getOrNull()?.let { result ->
            val libraryCardId = result.state.getLibrary(result.playerIds.first()).single()
            result.state.getEntity(libraryCardId)?.get<CardComponent>()?.name shouldBe "Outland Liberator"
        }
    }

    test("both locked curriculum files have 146 unique exact entries and all resolve") {
        val root = repositoryRoot()
        val akiri = root.resolve("docs/ml/curriculum/akiri-v0.1.txt")
        val chevill = root.resolve("docs/ml/curriculum/chevill-v0.1.txt")

        sha256(akiri) shouldBe "E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04"
        sha256(chevill) shouldBe "0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474"

        val akiriEntries = readDeckNames(akiri)
        val chevillEntries = readDeckNames(chevill)
        akiriEntries.size shouldBe 100
        chevillEntries.size shouldBe 100

        val exactEntries = (akiriEntries + chevillEntries).toSet()
        exactEntries.size shouldBe 146

        val unresolved = exactEntries.filterNot(registry::hasCard).sorted()
        println("Unresolved exact entries: $unresolved")
        unresolved shouldBe emptyList()
    }

    test("another catalog DFC uses the same generic combined-name boundary") {
        val excluded = setOf("Outland Liberator", "Garruk Relentless")
        val additionalDfc = MtgSetCatalog.all
            .flatMap { it.cards }
            .filter { it.isDoubleFaced }
            .filterNot { it.name in excluded }
            .sortedBy { it.name }
            .first()
        val combinedName = "${additionalDfc.name} // ${additionalDfc.backFace!!.name}"

        val failure = runCatching { registry.requireCard(combinedName) }.exceptionOrNull()
        failure?.let { exception ->
            println("Additional DFC '$combinedName' failure: ${exception::class.qualifiedName} code=${diagnosticCode(exception)}")
        }
        failure shouldBe null
        registry.requireCard(combinedName).name shouldBe additionalDfc.name
        (registry.requireCard(combinedName) === registry.requireCard(additionalDfc.name)) shouldBe true
    }

    test("front and back face names remain independently resolvable") {
        registry.requireCard("Outland Liberator").name shouldBe "Outland Liberator"
        registry.requireCard("Frenzied Trapbreaker").name shouldBe "Frenzied Trapbreaker"
        registry.requireCard("Garruk Relentless").name shouldBe "Garruk Relentless"
        registry.requireCard("Garruk, the Veil-Cursed").name shouldBe "Garruk, the Veil-Cursed"
        registry.getFrontFace("Frenzied Trapbreaker")?.name shouldBe "Outland Liberator"
        registry.getFrontFace("Garruk, the Veil-Cursed")?.name shouldBe "Garruk Relentless"
    }

    test("front-face collector-number lookup remains canonical and combined lookup is not invented") {
        val stamped = catalogRegistry()
        stamped.requireCard("Outland Liberator#MID-190").name shouldBe "Outland Liberator"
        stamped.requireCard("Garruk Relentless#ISD-181").name shouldBe "Garruk Relentless"
        stamped.getCard("$outlandCombined#MID-190").shouldBeNull()
        stamped.getCard("$garrukCombined#ISD-181").shouldBeNull()
    }

    test("combined aliases stay out of names, size, choice pools, and card lists") {
        val names = registry.allCardNames()

        names.contains(outlandCombined) shouldBe false
        names.contains(garrukCombined) shouldBe false
        registry.size shouldBe names.size
        registry.cardNamesIn(com.wingedsheep.sdk.scripting.CardNamePool.ANY)
            .contains(outlandCombined) shouldBe false
        registry.cardNamesIn(com.wingedsheep.sdk.scripting.CardNamePool.ANY)
            .contains(garrukCombined) shouldBe false
        registry.getCardsByName(outlandCombined).shouldBeEmpty()
        registry.getCardsByName(garrukCombined).shouldBeEmpty()
    }

    test("split cards remain canonical definitions and are not interpreted as DFC aliases") {
        val split = registry.requireCard("Pain // Suffering")

        split.name shouldBe "Pain // Suffering"
        split.layout shouldBe CardLayout.SPLIT
        registry.getCard("Pain") shouldBe null
        registry.getCard("Suffering") shouldBe null
    }

    test("an overlay resolves a combined name to its pinned front definition") {
        val pinned = registry.requireCard("Outland Liberator").copy()
        val overlay = CardRegistry(parent = registry).apply { register(pinned) }

        (overlay.requireCard(outlandCombined) === pinned) shouldBe true
        (registry.requireCard("Outland Liberator") === pinned) shouldBe false
    }

    test("a child combined alias shadows a parent canonical name in overlay enumerations") {
        val combinedName = "Synthetic Front // Synthetic Back"
        val parentCanonical = CardDefinition.sorcery(
            name = combinedName,
            manaCost = ManaCost.ZERO,
            oracleText = "",
        ).copy(layout = CardLayout.SPLIT)
        val dfc = syntheticDfc("Synthetic Front", "Synthetic Back")
        val parent = CardRegistry().apply { register(parentCanonical) }
        val overlay = CardRegistry(parent = parent).apply { register(dfc) }

        (overlay.requireCard(combinedName) === dfc) shouldBe true
        overlay.allCardNames().contains(combinedName) shouldBe false
        overlay.size shouldBe 2
        overlay.cardNamesIn(com.wingedsheep.sdk.scripting.CardNamePool.ANY)
            .contains(combinedName) shouldBe false
        overlay.nonlandCardNames().contains(combinedName) shouldBe false
        overlay.getCardsByName(combinedName).shouldBeEmpty()
    }

    test("a local canonical split name cannot be overwritten by a DFC alias") {
        val canonicalSplit = CardDefinition.sorcery(
            name = "Synthetic Front // Synthetic Back",
            manaCost = ManaCost.ZERO,
            oracleText = "",
        ).copy(layout = CardLayout.SPLIT)
        val dfc = syntheticDfc("Synthetic Front", "Synthetic Back")
        val local = CardRegistry().apply { register(canonicalSplit) }

        shouldThrow<IllegalArgumentException> { local.register(dfc) }
        local.requireCard(canonicalSplit.name) shouldBe canonicalSplit
    }

    test("rejects an incompatible canonical back face before registering a DFC") {
        val unrelatedBack = CardDefinition.creature(
            name = "Synthetic Back",
            manaCost = ManaCost.ZERO,
            subtypes = emptySet(),
            power = 99,
            toughness = 99,
        )
        val dfc = syntheticDfc("Synthetic Front", "Synthetic Back")
        val local = CardRegistry().apply { register(unrelatedBack) }

        shouldThrow<IllegalArgumentException> { local.register(dfc) }
        local.requireCard("Synthetic Back") shouldBe unrelatedBack
        local.getFrontFace("Synthetic Back").shouldBeNull()
        local.getCard("Synthetic Front") shouldBe null
        local.getCard("Synthetic Front // Synthetic Back") shouldBe null
    }

    test("rejects an incompatible canonical registration over an existing DFC back face") {
        val dfc = syntheticDfc("Synthetic Front", "Synthetic Back")
        val unrelatedBack = CardDefinition.creature(
            name = "Synthetic Back",
            manaCost = ManaCost.ZERO,
            subtypes = emptySet(),
            power = 99,
            toughness = 99,
        )
        val local = CardRegistry().apply { register(dfc) }

        shouldThrow<IllegalArgumentException> { local.register(unrelatedBack) }
        (local.requireCard("Synthetic Back") === dfc.backFace) shouldBe true
        (local.requireCard("Synthetic Front // Synthetic Back") === dfc) shouldBe true
        local.getFrontFace("Synthetic Back")?.name shouldBe "Synthetic Front"
    }

    test("allows identical back-face re-registration without losing DFC linkage") {
        val dfc = syntheticDfc("Synthetic Front", "Synthetic Back")
        val local = CardRegistry().apply { register(dfc) }

        local.register(dfc.backFace!!)

        (local.requireCard("Synthetic Back") === dfc.backFace) shouldBe true
        (local.requireCard("Synthetic Front // Synthetic Back") === dfc) shouldBe true
        local.getFrontFace("Synthetic Back")?.name shouldBe "Synthetic Front"
    }

    test("incompatible local DFC aliases are rejected and identical re-registration is idempotent") {
        val first = syntheticDfc("Synthetic Front // Left", "Right")
        val incompatible = syntheticDfc("Synthetic Front", "Left // Right")
        val local = CardRegistry().apply { register(first) }

        shouldThrow<IllegalArgumentException> { local.register(incompatible) }
        local.register(first)
        local.requireCard("Synthetic Front // Left // Right") shouldBe first
    }

    test("unknown names retain the stable CARD_DEFINITION_MISSING diagnostic") {
        val failure = runCatching { registry.requireCard("Definitely Not A Real Card") }.exceptionOrNull()

        (failure is CardDefinitionMissingException) shouldBe true
        diagnosticCode(failure!!) shouldBe "CARD_DEFINITION_MISSING"
    }
})

private fun catalogRegistry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards.map { it.withSetCodeIfMissing(set.code) })
        register(set.basicLands.map { it.withSetCodeIfMissing(set.code) })
    }
}

private fun CardDefinition.withSetCodeIfMissing(setCode: String): CardDefinition =
    if (this.setCode == null) copy(setCode = setCode) else this

private fun diagnosticCode(failure: Throwable): String? =
    (failure as? CardDefinitionMissingException)?.code

private fun repositoryRoot(): Path {
    var candidate = Paths.get("").toAbsolutePath().normalize()
    while (!Files.exists(candidate.resolve("docs/ml/curriculum/akiri-v0.1.txt"))) {
        candidate = candidate.parent ?: error("Could not locate repository root from ${Paths.get("").toAbsolutePath()}")
    }
    return candidate
}

private fun readDeckNames(path: Path): List<String> =
    Files.readAllLines(path)
        .asSequence()
        .filter { it.length >= 4 && it[0].isDigit() && it[1].isDigit() && it[2].isDigit() && it[3] == '\t' }
        .map { it.substringAfterLast('\t') }
        .toList()

private fun sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256")
        // Git checkouts use platform-dependent line endings; the locked hashes are defined over
        // the repository's CRLF text representation, so canonicalize before hashing.
        .digest(
            Files.readString(path)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\n", "\r\n")
                .toByteArray(Charsets.UTF_8),
        )
        .joinToString("") { "%02X".format(it) }

private fun syntheticDfc(frontName: String, backName: String): CardDefinition =
    CardDefinition.doubleFacedCreature(
        frontFace = CardDefinition.creature(
            name = frontName,
            manaCost = ManaCost.ZERO,
            subtypes = emptySet(),
            power = 1,
            toughness = 1,
        ),
        backFace = CardDefinition.creature(
            name = backName,
            manaCost = ManaCost.ZERO,
            subtypes = emptySet(),
            power = 2,
            toughness = 2,
        ),
    )
