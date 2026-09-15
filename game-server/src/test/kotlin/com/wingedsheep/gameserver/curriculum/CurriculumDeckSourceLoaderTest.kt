package com.wingedsheep.gameserver.curriculum

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class CurriculumDeckSourceLoaderTest : FunSpec({

    val loader = CurriculumDeckSourceLoader()

    test("loads both locked sources with exact bytes, commanders, and 100 rows") {
        val akiri = loader.load("docs/ml/curriculum/akiri-v0.1.txt")
        val chevill = loader.load("docs/ml/curriculum/chevill-v0.1.txt")

        akiri.sourceDigest shouldBe "E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04"
        chevill.sourceDigest shouldBe "0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474"
        akiri.commander shouldBe "Akiri, Fearless Voyager"
        chevill.commander shouldBe "Chevill, Bane of Monsters"
        akiri.cardCount shouldBe 100
        chevill.cardCount shouldBe 100
        akiri.deckList.values.sum() shouldBe 100
        chevill.deckList.values.sum() shouldBe 100
        akiri.asCommanderDeck().size shouldBe 100
        chevill.asCommanderDeck().size shouldBe 100
        akiri.asCommanderDeck().cards shouldHaveSize 99
        chevill.asCommanderDeck().cards shouldHaveSize 99
    }

    test("resolves only the server-owned curriculum preset identities") {
        CurriculumAiTournamentPreset.fromRequest("MTG_ML_AKIRI_CHEVILL_COMMANDER") shouldBe
            CurriculumAiTournamentPreset.AKIRI_CHEVILL
        CurriculumAiTournamentPreset.fromRequest("argentum-mtg-ml-akiri-chevill-curriculum@v1") shouldBe
            CurriculumAiTournamentPreset.AKIRI_CHEVILL
        shouldThrow<IllegalArgumentException> {
            CurriculumAiTournamentPreset.fromRequest("docs/ml/curriculum/akiri-v0.1.txt")
        }
    }

    test("rejects malformed rows, blank names, and unsafe paths") {
        withSource(
            "# Commander: Test Commander\n001\tCOMMANDER\t-\tTest Commander\n002\tLAND\t-",
        ) { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }
        withSource(
            "# Commander: Test Commander\n001\tCOMMANDER\t-\tTest Commander\n002\tLAND\t-\t",
        ) { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }
        shouldThrow<IllegalArgumentException> { loader.load("../outside.txt") }
    }

    test("rejects duplicate or non-contiguous slots and wrong row count") {
        withSource(
            "# Commander: Test Commander\n001\tCOMMANDER\t-\tTest Commander\n003\tLAND\t-\tForest",
        ) { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }

        val rows = buildString {
            appendLine("# Commander: Test Commander")
            for (slot in 1..99) {
                appendLine("%03d\t%s\t-\t%s".format(slot, if (slot == 1) "COMMANDER" else "LAND", if (slot == 1) "Test Commander" else "Forest"))
            }
        }
        withSource(rows) { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }
    }

    test("rejects missing or disagreeing commander declarations") {
        val rows = (1..100).joinToString("\n") { slot ->
            "%03d\t%s\t-\t%s".format(slot, if (slot == 1) "COMMANDER" else "LAND", if (slot == 1) "Row Commander" else "Forest")
        }
        withSource(rows) { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }
        withSource("# Commander: Header Commander\n$rows") { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }
        withSource("# Commander: Row Commander\n$rows\n101\tCOMMANDER\t-\tSecond Commander") { path, root ->
            shouldThrow<IllegalArgumentException> { CurriculumDeckSourceLoader(root).load(path.fileName.toString()) }
        }
    }
}) {
    companion object {
        private fun withSource(content: String, block: (Path, Path) -> Unit) {
            val root = Files.createTempDirectory("curriculum-source-test")
            val path = root.resolve("source.txt")
            try {
                Files.writeString(path, content)
                block(path, root)
            } finally {
                Files.deleteIfExists(path)
                Files.deleteIfExists(root)
            }
        }
    }
}
