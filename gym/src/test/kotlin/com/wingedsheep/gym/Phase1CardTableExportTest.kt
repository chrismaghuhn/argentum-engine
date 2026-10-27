package com.wingedsheep.gym

import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

private val phase1CardTableEnabled = System.getProperty("phase1.cardTable") == "true"

/**
 * Opt-in export of the P1 card table (`argentum-p1-card-table@v1`): per card name the static
 * facts the training samples do not carry — printed rules text, printed mana cost and base
 * subtypes — straight from the server's card registry. The Python side joins it to samples by
 * name (docs/ml/p1-card-text-features.md), so existing data needs no re-collection.
 *
 * `-Dphase1.cardTable=true -Dphase1.cardTableOut=<file.json>`
 */
class Phase1CardTableExportTest : FunSpec({
    test("exports the P1 card table from the card registry")
        .config(enabled = phase1CardTableEnabled) {
            val repositoryRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
            val out = Path.of(
                System.getProperty("phase1.cardTableOut") ?: repositoryRoot.resolve("gym/build/phase1-card-table.json").toString(),
            )
            val registry = Phase1Tournament.Registries.card
            val sourceCommit = runCatching {
                ProcessBuilder("git", "rev-parse", "HEAD").directory(repositoryRoot.toFile())
                    .start().inputStream.bufferedReader().readText().trim()
            }.getOrDefault("unknown")
            val cards = registry.allCardNames().sorted().mapNotNull { name ->
                val card = registry.getCard(name) ?: return@mapNotNull null
                name to buildJsonObject {
                    put("oracleText", card.oracleText)
                    put("manaCost", card.manaCost.toString())
                    put("subtypes", JsonArray(card.typeLine.subtypes.map { it.value }.sorted().map(::JsonPrimitive)))
                }
            }
            Files.createDirectories(out.parent)
            Files.writeString(
                out,
                buildJsonObject {
                    put("schema", "argentum-p1-card-table@v1")
                    put("sourceCommit", sourceCommit)
                    put("cards", JsonObject(cards.toMap()))
                }.toString(),
            )
            println("# card table: ${cards.size} cards -> $out")
        }
})
