package com.wingedsheep.engine.handlers.effects.permanent.attachments

import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PermanentAttachedEvent
import com.wingedsheep.engine.core.PermanentUnattachedEvent
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.TimestampComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.KeywordAbility
import com.wingedsheep.sdk.scripting.ProtectionScope
import com.wingedsheep.sdk.scripting.effects.CardSource
import com.wingedsheep.sdk.scripting.effects.CollectionFilter
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * RED-first characterization for the generic mixed attachment-transfer primitive.
 *
 * The test card is deliberately local: production card definitions must not become an Ardenn
 * handler. The engine behavior under test is the reusable pipeline primitive itself.
 */
class AttachCollectionToTargetExecutorTest : ScenarioTestBase() {

    private val transferSpell = card("Test Attachment Transfer") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Attach any number of selected attachments to target permanent or player."
        spell {
            target = Targets.PermanentOrPlayer
            effect = Effects.Pipeline {
                val candidates = gather(CardSource.ControlledPermanents(), name = "candidates")
                val legal = filter(
                    candidates,
                    CollectionFilter.AttachableTo(EffectTarget.ContextTarget(0)),
                    name = "legal"
                )
                val selected = chooseAnyNumber(legal, name = "selected")
                attach(selected, EffectTarget.ContextTarget(0))
            }
        }
    }

    private val equipmentA = card("Test Transfer Equipment A") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val equipmentB = card("Test Transfer Equipment B") {
        manaCost = "{0}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    private val playerAura = card("Test Transfer Player Aura") {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant player"
        auraTarget = Targets.Player
    }

    private val creatureAura = card("Test Transfer Creature Aura") {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant creature"
        auraTarget = Targets.Creature
    }

    private val dualPlayerAttachment = card("Test Transfer Dual Attachment") {
        manaCost = "{0}"
        typeLine = "Artifact Enchantment — Aura Equipment"
        oracleText = "Enchant player; Equip {0}"
        auraTarget = Targets.Player
        equipAbility("{0}")
    }

    private val creatureEquipment = card("Test Transfer Creature Equipment") {
        manaCost = "{0}"
        typeLine = "Artifact Creature — Equipment"
        oracleText = "Equip {0}"
        power = 3
        toughness = 3
        equipAbility("{0}")
    }

    private val permanentAura = card("Test Transfer Permanent Aura") {
        manaCost = "{0}"
        typeLine = "Enchantment — Aura"
        oracleText = "Enchant permanent"
        auraTarget = Targets.Permanent
    }

    private val artifactProtectedCreature = card("Test Transfer Artifact-Protected Creature") {
        manaCost = "{0}"
        typeLine = "Creature — Human"
        oracleText = "Protection from artifacts"
        power = 2
        toughness = 2
        keywordAbility(KeywordAbility.Protection(ProtectionScope.CardType("Artifact")))
    }

    private val battleEquipment = card("Test Transfer Battle Equipment") {
        manaCost = "{0}"
        typeLine = "Artifact Battle — Equipment"
        oracleText = "Equip {0}"
        equipAbility("{0}")
    }

    init {
        cardRegistry.register(
            listOf(
                transferSpell,
                equipmentA,
                equipmentB,
                playerAura,
                creatureAura,
                dualPlayerAttachment,
                creatureEquipment,
                permanentAura,
                artifactProtectedCreature,
                battleEquipment,
            )
        )

        test("selects mixed candidates and orders a multi-attachment batch before mutation") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val sourceHost = game.findPermanent("Grizzly Bears")!!
            val destination = game.findPermanent("Hill Giant")!!
            val first = game.findPermanent(equipmentA.name)!!
            val second = game.findPermanent(equipmentB.name)!!

            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()

            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options.shouldContainExactlyInAnyOrder(first, second)
            game.selectCards(listOf(first, second)).error shouldBe null

            val order = game.getPendingDecision().shouldBeInstanceOf<OrderObjectsDecision>()
            order.objects.shouldContainExactlyInAnyOrder(first, second)

            // The order decision is a semantic boundary, not a sequential attach loop.
            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe sourceHost
            game.state.getEntity(second)?.get<AttachedToComponent>()?.targetId shouldBe sourceHost
            game.state.getEntity(first)?.get<TimestampComponent>() shouldBe null
            game.state.getEntity(second)?.get<TimestampComponent>() shouldBe null

            game.submitObjectOrdering(listOf(second, first)).error shouldBe null

            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe destination
            game.state.getEntity(second)?.get<AttachedToComponent>()?.targetId shouldBe destination
            game.state.getEntity(first)?.get<TimestampComponent>() shouldNotBe null
            game.state.getEntity(second)?.get<TimestampComponent>() shouldNotBe null
            game.state.getEntity(first)?.get<TimestampComponent>()!!.timestamp shouldBe
                game.state.getEntity(second)?.get<TimestampComponent>()!!.timestamp + 1
            game.state.getEntity(destination)?.get<AttachmentsComponent>()?.attachedIds
                .orEmpty().shouldContainExactlyInAnyOrder(first, second)
        }

        test("preserves a same-host attachment as a no-op without a fresh timestamp") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val host = game.findPermanent("Grizzly Bears")!!
            val equipment = game.findPermanent(equipmentA.name)!!

            game.castSpell(1, transferSpell.name, host).error shouldBe null
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options shouldBe listOf(equipment)
            game.selectCards(listOf(equipment)).error shouldBe null
            game.getPendingDecision() shouldBe null

            game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe host
            game.state.getEntity(equipment)?.get<TimestampComponent>() shouldBe null
        }

        test("publishes only legal Aura candidates for a player destination and applies the Aura path") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, playerAura.name, "Grizzly Bears")
                .withCardAttachedTo(1, creatureAura.name, "Grizzly Bears")
                .withCardAttachedTo(1, dualPlayerAttachment.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val host = game.findPermanent("Grizzly Bears")!!
            val equipment = game.findPermanent(equipmentA.name)!!
            val playerAuraId = game.findPermanent(playerAura.name)!!
            val creatureAuraId = game.findPermanent(creatureAura.name)!!
            val dualId = game.findPermanent(dualPlayerAttachment.name)!!

            game.castSpellTargetingPlayer(1, transferSpell.name, 2).error shouldBe null
            game.resolveStack()
            val selection = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            selection.options.shouldContainExactly(playerAuraId)
            selection.options shouldNotBe listOf(equipment, creatureAuraId, dualId)

            game.selectCards(listOf(playerAuraId)).error shouldBe null
            game.getPendingDecision() shouldBe null
            game.state.getEntity(playerAuraId)?.get<AttachedToComponent>()?.targetId shouldBe game.player2Id
            game.state.getEntity(host)?.get<AttachmentsComponent>()?.attachedIds
                .orEmpty() shouldNotBe listOf(playerAuraId)
        }

        test("attaches a controlled Equipment to an opponent's creature") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardOnBattlefield(2, "Hill Giant")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val destination = game.findPermanent("Hill Giant")!!
            val equipment = game.findPermanent(equipmentA.name)!!
            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(listOf(equipment)).error shouldBe null
            game.getPendingDecision() shouldBe null
            game.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe destination
        }

        test("dual Aura Equipment requires both Aura and Equipment legality") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardAttachedTo(1, dualPlayerAttachment.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val dualId = game.findPermanent(dualPlayerAttachment.name)!!
            game.castSpellTargetingPlayer(1, transferSpell.name, 2).error shouldBe null
            game.resolveStack()
            // The complete legal domain is empty, so ChooseAnyNumber auto-selects nothing.
            game.getPendingDecision() shouldBe null
            game.state.getEntity(dualId)?.get<AttachedToComponent>()?.targetId shouldBe
                game.findPermanent("Grizzly Bears")!!
        }

        test("excludes a creature Equipment when reconfigure support is unavailable") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, creatureEquipment.name)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val destination = game.findPermanent("Grizzly Bears")!!
            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()

            game.getPendingDecision() shouldBe null
        }

        test("rejects an Equipment as its own host") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, creatureEquipment.name)
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val equipment = game.findPermanent(creatureEquipment.name)!!
            game.castSpell(1, transferSpell.name, equipment).error shouldBe null
            game.resolveStack()

            game.getPendingDecision() shouldBe null
        }

        test("rejects an Aura as its own host") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, permanentAura.name)
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val aura = game.findPermanent(permanentAura.name)!!
            game.castSpell(1, transferSpell.name, aura).error shouldBe null
            game.resolveStack()

            game.getPendingDecision() shouldBe null
        }

        test("rejects an Equipment from a creature with protection from artifacts") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, equipmentA.name)
                .withCardOnBattlefield(1, artifactProtectedCreature.name)
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val destination = game.findPermanent(artifactProtectedCreature.name)!!
            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()

            game.getPendingDecision() shouldBe null
        }

        test("rejects a Battle even when it is also an Equipment") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, battleEquipment.name)
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()

            val destination = game.findPermanent("Grizzly Bears")!!
            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()

            game.getPendingDecision() shouldBe null
        }

        test("revalidates selected object identity and resolves the valid survivor") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()
            val destination = game.findPermanent("Hill Giant")!!
            val first = game.findPermanent(equipmentA.name)!!
            val second = game.findPermanent(equipmentB.name)!!

            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(listOf(first, second)).error shouldBe null
            game.getPendingDecision().shouldBeInstanceOf<OrderObjectsDecision>()

            val oldStamp = game.state.objectIdentityStamps[first] ?: error("missing test identity stamp")
            game.state = game.state.copy(
                objectIdentityStamps = game.state.objectIdentityStamps + (first to oldStamp + 1L)
            )
            val result = game.submitObjectOrdering(listOf(second, first))
            result.error shouldBe null
            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe
                game.findPermanent("Grizzly Bears")!!
            game.state.getEntity(second)?.get<AttachedToComponent>()?.targetId shouldBe destination
            result.events.count { it is PermanentUnattachedEvent } shouldBe 1
            result.events.count { it is PermanentAttachedEvent } shouldBe 1
        }

        test("rejects a changed target identity before any batch mutation") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()
            val destination = game.findPermanent("Hill Giant")!!
            val first = game.findPermanent(equipmentA.name)!!
            val second = game.findPermanent(equipmentB.name)!!

            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(listOf(first, second)).error shouldBe null
            val paused = game.state
            val oldStamp = paused.objectIdentityStamps[destination] ?: error("missing target identity stamp")
            game.state = paused.copy(
                objectIdentityStamps = paused.objectIdentityStamps + (destination to oldStamp + 1L)
            )

            val result = game.submitObjectOrdering(listOf(second, first))
            result.error shouldNotBe null
            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe
                game.findPermanent("Grizzly Bears")!!
            game.state.getEntity(second)?.get<AttachedToComponent>()?.targetId shouldBe
                game.findPermanent("Grizzly Bears")!!
            result.events shouldBe emptyList()
        }

        test("round-trips the paused order continuation and rejects timestamp overflow atomically") {
            val game = scenario()
                .withPlayers()
                .withCardOnBattlefield(1, "Grizzly Bears")
                .withCardOnBattlefield(1, "Hill Giant")
                .withCardAttachedTo(1, equipmentA.name, "Grizzly Bears")
                .withCardAttachedTo(1, equipmentB.name, "Grizzly Bears")
                .withCardInHand(1, transferSpell.name)
                .withActivePlayer(1)
                .build()
            val source = game.findPermanent("Grizzly Bears")!!
            val destination = game.findPermanent("Hill Giant")!!
            val first = game.findPermanent(equipmentA.name)!!
            val second = game.findPermanent(equipmentB.name)!!

            game.castSpell(1, transferSpell.name, destination).error shouldBe null
            game.resolveStack()
            game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
            game.selectCards(listOf(first, second)).error shouldBe null
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
                allowStructuredMapKeys = true
            }
            val encoded = json.encodeToString(com.wingedsheep.engine.state.GameState.serializer(), game.state)
            val restored = json.decodeFromString(com.wingedsheep.engine.state.GameState.serializer(), encoded)
            restored shouldBe game.state
            game.state = restored.copy(timestamp = Long.MAX_VALUE)

            val before = game.state
            val result = game.submitObjectOrdering(listOf(second, first))
            result.error shouldNotBe null
            result.events shouldBe emptyList()
            game.state shouldBe before
            game.state.getEntity(first)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.state.getEntity(second)?.get<AttachedToComponent>()?.targetId shouldBe source
            game.state.getEntity(first)?.get<TimestampComponent>() shouldBe null
            game.state.getEntity(second)?.get<TimestampComponent>() shouldBe null
        }
    }
}
