package com.wingedsheep.gym

import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.mtg.sets.MtgSetCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readLines

/** First red/green slice of the durable exact-pair Environment V1 acceptance gate. */
class EnvironmentV1ExactPairAcceptanceTest : FunSpec({

    test("the locked Akiri and Chevill files resolve exactly 146 unique cards") {
        val akiri = readLockedDeck("akiri-v0.1.txt")
        val chevill = readLockedDeck("chevill-v0.1.txt")
        val uniqueCards = (akiri.cards + chevill.cards).distinct()
        val registry = CardRegistry().apply {
            MtgSetCatalog.all.forEach { set ->
                register(set.cards)
                register(set.basicLands)
            }
        }

        akiri.cards.size shouldBe 100
        chevill.cards.size shouldBe 100
        uniqueCards.size shouldBe 146
        akiri.commander shouldBe "Akiri, Fearless Voyager"
        chevill.commander shouldBe "Chevill, Bane of Monsters"
        uniqueCards.filterNot(registry::hasCard) shouldBe emptyList()
    }
}) {
    private data class LockedDeck(
        val commander: String,
        val cards: List<String>
    )

    companion object {
        private fun readLockedDeck(fileName: String): LockedDeck {
            val workingDirectory = Path.of(System.getProperty("user.dir"))
            val repositoryRoot = generateSequence(workingDirectory) { it.parent }
                .first { it.resolve("docs/ml/curriculum").toFile().isDirectory }
            val path = repositoryRoot.resolve("docs/ml/curriculum/$fileName")
            val cards = path.readLines()
                .filter { it.matches(Regex("^\\d{3}\\t.*")) }
                .map { it.substringAfterLast('\t') }
            return LockedDeck(
                commander = cards.first(),
                cards = cards,
            )
        }
    }
}
