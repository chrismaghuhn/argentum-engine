package com.wingedsheep.gym

import com.wingedsheep.ai.engine.SealedDeckGenerator
import com.wingedsheep.engine.limited.BoosterGenerator
import com.wingedsheep.mtg.sets.MtgSetCatalog
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

private val phase1SealedEnabled = System.getProperty("phase1.sealed") == "true"

/**
 * Opt-in export of a sealed deck pool for P1 (`-Dphase1.decks` format: `{"Deck": {"Card": count}}`).
 * The engine's [SealedDeckGenerator] opens boosters with unseeded randomness, so decks are generated
 * once here and stored; collection, tournaments and PPO then draw pairings from the fixed pool and stay
 * reproducible. Booster setup mirrors the gym server (sets flagged `sealedSupported`).
 *
 * `-Dphase1.sealed=true -Dphase1.sealedSet=FDN -Dphase1.sealedDecks=2000 -Dphase1.sealedOut=<file.json>`
 * (`-Dphase1.sealedSet=list` only prints the supported sets with their card counts).
 */
class Phase1SealedPoolExportTest : FunSpec({
    test("exports a sealed deck pool")
        .config(enabled = phase1SealedEnabled) {
            val sets = MtgSetCatalog.all.filter { it.sealedSupported }
            val generator = BoosterGenerator(
                sets.associate { set ->
                    set.code to BoosterGenerator.SetConfig(
                        setCode = set.code,
                        setName = set.displayName,
                        cards = set.cards,
                        basicLands = (set.basicLandsFallback ?: set).basicLands,
                        incomplete = set.incomplete,
                        block = set.block,
                        boosterStrategy = set.boosterStrategy,
                    )
                },
            )
            val setCode = System.getProperty("phase1.sealedSet") ?: "list"
            if (setCode == "list") {
                sets.sortedBy { it.code }.forEach { println("# set ${it.code} cards=${it.cards.size} incomplete=${it.incomplete} ${it.displayName}") }
                return@config
            }
            val count = System.getProperty("phase1.sealedDecks")?.toInt() ?: 2000
            val out = Path.of(checkNotNull(System.getProperty("phase1.sealedOut")) { "phase1.sealedOut" })
            val sealed = SealedDeckGenerator(generator)
            val registry = Phase1Tournament.Registries.card
            val decks = (1..count).associate { index ->
                // Basic-land art variants ("Swamp#BLB-270") resolve to their plain name for the deck pool.
                val deck = sealed.generate(setCode).entries.groupBy({ it.key.substringBefore('#') }, { it.value })
                    .mapValues { (_, counts) -> counts.sum() }
                "%s Sealed %04d".format(setCode, index) to deck
            }
            val unknown = decks.values.flatMap { it.keys }.toSet().filterNot { registry.getCard(it) != null }
            check(unknown.isEmpty()) { "cards missing from the registry: ${unknown.take(10)}" }
            Files.createDirectories(out.toAbsolutePath().parent)
            Files.writeString(
                out,
                JsonObject(decks.mapValues { (_, deck) -> JsonObject(deck.toSortedMap().mapValues { JsonPrimitive(it.value) }) }).toString(),
            )
            val sizes = decks.values.map { it.values.sum() }
            println("# sealed pool: ${decks.size} decks from $setCode, sizes ${sizes.min()}..${sizes.max()}, " +
                "${decks.values.flatMap { it.keys }.toSet().size} distinct cards -> $out")
        }
})
