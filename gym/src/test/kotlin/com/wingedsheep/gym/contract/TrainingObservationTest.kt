package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TrainingObservationTest : FunSpec({

    fun createRegistry(): CardRegistry {
        val registry = CardRegistry()
        registry.register(PortalSet.cards)
        registry.register(PortalSet.basicLands)
        return registry
    }

    fun simpleDeck() = Deck.of("Mountain" to 17, "Raging Goblin" to 3)

    fun newEnv(): GameEnvironment {
        val env = GameEnvironment.create(createRegistry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", simpleDeck()),
                    PlayerConfig("Bob", simpleDeck())
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    test("observation includes all basic state fields and round-trips through JSON") {
        val env = newEnv()
        val perspective = env.playerIds[0]
        val result = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, perspective, env.legalActions())

        val obs = result.observation as TrainingObservation
        obs.perspectivePlayerId shouldBe perspective
        obs.players.size shouldBe 2
        obs.agentToAct.shouldNotBeNull()
        obs.terminated.shouldBeFalse()
        obs.schemaHash shouldBe SchemaHash.CURRENT
        obs.stateDigest shouldMatch Regex("[0-9a-f]{64}")
        obs.legalActions.shouldNotBeEmpty()
        obs.legalActions.forEach { action ->
            val targetDomain = action.targetDomain.shouldNotBeNull()
            targetDomain.version shouldBe ACTION_TARGET_DOMAIN_VERSION
            targetDomain.composition shouldBe ActionTargetComposition.FIXED
        }

        // Every player-keyed zone in the contract's fixed order (incl. command and sideboard);
        // the stack has its own ordered field.
        obs.zones.size shouldBe 2 * TRAINING_OBSERVATION_ZONE_ORDER.size

        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        encoded shouldContain "\"targetDomain\""
        encoded shouldContain "\"requiredPayloadFields\""
        val decoded = json.decodeFromString(TrainingObservation.serializer(), encoded)
        decoded shouldBe obs
    }

    test("per-player zones have an explicit complete schema order") {
        TRAINING_OBSERVATION_ZONE_ORDER.shouldContainExactly(
            Zone.HAND,
            Zone.LIBRARY,
            Zone.GRAVEYARD,
            Zone.EXILE,
            Zone.BATTLEFIELD,
            Zone.COMMAND,
            Zone.SIDEBOARD,
        )
        // Every engine zone is classified in the contract itself, so a new one cannot be absorbed
        // by relaxing this test — it has to be named either per-player or explicitly not.
        TRAINING_OBSERVATION_ZONE_ORDER.toSet() + NON_PLAYER_KEYED_ZONES shouldBe
            Zone.entries.toSet()
        TRAINING_OBSERVATION_ZONE_ORDER.toSet() shouldNotContainAnyOf NON_PLAYER_KEYED_ZONES

        val env = newEnv()
        val perspective = env.playerIds[0]
        val observation = ObservationBuilder(env.cardRegistry)
            .build(env.state, perspective, env.legalActions())
            .observation as TrainingObservation

        observation.zones
            .filter { it.ownerId == perspective }
            .map { it.zoneType }
            .shouldContainExactly(TRAINING_OBSERVATION_ZONE_ORDER)
    }

    test("an actionId from the observation resolves to a steppable action") {
        val env = newEnv()
        val perspective = env.playerIds[0]
        val result = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, perspective, env.legalActions())

        val passView = result.observation.legalActions.first { it.description.contains("Pass", ignoreCase = true) || it.kind.contains("Pass", ignoreCase = true) }
        val resolved = result.registry.resolve(passView.actionId)
        resolved.shouldNotBe(ResolvedAction.Unknown)

        val legal = resolved as ResolvedAction.Legal
        (legal.action is PassPriority).shouldBeTrue()

        val stepResult = env.step(legal.action)
        stepResult.state.shouldNotBeNull()
        env.stepCount shouldBeGreaterThan 0
    }

    test("opponent hand and every library stay masked") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]

        val masked = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions())
            .observation as TrainingObservation
        val myHand = masked.zones.single { it.ownerId == me && it.zoneType == Zone.HAND }
        val theirHand = masked.zones.single { it.ownerId == opponent && it.zoneType == Zone.HAND }
        myHand.hidden.shouldBeFalse()
        myHand.cards.size shouldBe myHand.size
        theirHand.hidden.shouldBeTrue()
        theirHand.cards.size shouldBe 0
        theirHand.size shouldBeGreaterThan 0

        // Every library is hidden regardless of perspective.
        masked.zones.filter { it.zoneType == Zone.LIBRARY }.forEach { it.hidden.shouldBeTrue() }

    }

    test("oracle text is serialized for cards in visible zones") {
        // Raging Goblin's printed text is "Haste" — that's what the agent
        // needs to see to know the creature can attack the turn it enters.
        val env = newEnv()
        val me = env.playerIds[0]

        val obs = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation as TrainingObservation
        val myHand = obs.zones.single { it.ownerId == me && it.zoneType == Zone.HAND }

        // At least one card should be in the opening hand; every card there
        // is fully visible to the perspective player including its oracle text.
        myHand.cards.shouldNotBeEmpty()
        val rages = myHand.cards.filter { it.name == "Raging Goblin" }
        if (rages.isNotEmpty()) {
            rages.first().oracleText shouldContain "Haste"
        }
        // Round-trip through JSON — oracle text must survive.
        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        val decoded = json.decodeFromString(TrainingObservation.serializer(), encoded)
        decoded.zones.flatMap { it.cards }.forEach { c ->
            // Every serialized card either has empty oracle text or preserves it.
            c.oracleText shouldBe (obs.zones.flatMap { it.cards }.first { it.entityId == c.entityId }.oracleText)
        }
    }

    // Upstream's version of the next two tests peeked at the opponent's HAND. The merged Gym keeps
    // the fork's visibility encoding: an individually revealed LIBRARY card is emitted inside its
    // still-hidden zone, while a revealed hand card stays out of the hand view (it is addressable
    // only through action domains — pinned by ObservationPrivacyTest "an explicitly revealed
    // hidden-zone object may be addressed without identity in the domain"). So the known-subset
    // representation is exercised on the library, and the hand half asserts the masked contract.
    test("a card revealed to this perspective is visible inside its still-hidden zone") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val opponentLibrary = env.state.getZone(ZoneKey(opponent, Zone.LIBRARY))
        val opponentHand = env.state.getZone(ZoneKey(opponent, Zone.HAND))
        opponentLibrary.size shouldBeGreaterThan 1
        opponentHand.size shouldBeGreaterThan 1

        fun observe(state: GameState) =
            ObservationBuilder(env.cardRegistry).build(state, me, env.legalActions()).observation as TrainingObservation

        fun revealing(cardId: EntityId): GameState =
            env.state.withEntity(
                cardId,
                env.state.getEntity(cardId)!!.with(RevealedToComponent.to(me))
            )

        fun opponentZoneView(obs: TrainingObservation, zone: Zone) =
            obs.zones.first { it.ownerId == opponent && it.zoneType == zone }

        opponentZoneView(observe(env.state), Zone.LIBRARY).cards.shouldBeEmpty()

        // Not the top card, so no top-of-library rule is involved — only the individual reveal.
        val revealed = opponentZoneView(observe(revealing(opponentLibrary[1])), Zone.LIBRARY)
        // Seeing one card does not unhide the zone: the rest of the library is still unknown,
        // so `size` stays the true count while `cards` holds only what this player has seen.
        revealed.hidden.shouldBeTrue()
        revealed.size shouldBe opponentLibrary.size
        revealed.cards.map { it.entityId } shouldBe listOf(opponentLibrary[1])

        val peekedHand = opponentZoneView(observe(revealing(opponentHand[0])), Zone.HAND)
        peekedHand.hidden.shouldBeTrue()
        peekedHand.size shouldBe opponentHand.size
        peekedHand.cards.shouldBeEmpty()
    }

    test("stateDigest distinguishes which card is known inside a hidden zone") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val opponentLibrary = env.state.getZone(ZoneKey(opponent, Zone.LIBRARY))

        fun digestRevealing(cardId: EntityId): String =
            ObservationBuilder(env.cardRegistry).build(
                env.state.withEntity(
                    cardId,
                    env.state.getEntity(cardId)!!.with(RevealedToComponent.to(me))
                ),
                me,
                env.legalActions()
            ).observation.stateDigest

        val nothingKnown = ObservationBuilder(env.cardRegistry).build(env.state, me, env.legalActions())
            .observation.stateDigest

        digestRevealing(opponentLibrary[0]) shouldNotBe nothingKnown
        digestRevealing(opponentLibrary[0]) shouldNotBe digestRevealing(opponentLibrary[1])
    }

    test("a spell on the stack reports its chosen targets, and they reach the digest") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val handKey = ZoneKey(me, Zone.HAND)
        val spellId = env.state.getZone(handKey).first()

        fun casting(target: EntityId): GameState {
            val hand = env.state.getZone(handKey)
            return env.state
                .copy(zones = env.state.zones + (handKey to hand - spellId), stack = listOf(spellId))
                .withEntity(
                    spellId,
                    env.state.getEntity(spellId)!!
                        // A real cast stamps the caster as controller on the way to the stack.
                        .with(ControllerComponent(me))
                        .with(TargetsComponent(listOf(ChosenTarget.Player(target))))
                )
        }

        fun observe(state: GameState) =
            ObservationBuilder(env.cardRegistry).build(state, me, env.legalActions()).observation as TrainingObservation

        val atOpponent = observe(casting(opponent))
        val item = atOpponent.stack.single()
        item.name.shouldNotBeEmpty()
        item.controllerId shouldBe me
        item.targets shouldBe listOf(opponent)

        // Same spell, other target: an agent that cannot tell these apart cannot search.
        atOpponent.stateDigest shouldNotBe observe(casting(me)).stateDigest
    }

    test("a triggered ability on the stack reports its source and controller") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val abilityId = EntityId.of("trigger-on-stack")
        val state = env.state
            .withEntity(
                abilityId,
                ComponentContainer.of(
                    TriggeredAbilityOnStackComponent(
                        sourceId = EntityId.of("source"),
                        sourceName = "Raging Goblin",
                        controllerId = opponent,
                        effect = Effects.DrawCards(1),
                        description = "Draw a card."
                    )
                )
            )
            .copy(stack = listOf(abilityId))

        val item = (ObservationBuilder(env.cardRegistry).build(state, me, env.legalActions())
            .observation as TrainingObservation).stack.single()

        // An ability is its own entity with no CardComponent, so it used to arrive unnamed,
        // uncontrolled and classified OTHER — three fields the digest now hashes.
        item.kind shouldBe StackItemKind.TRIGGERED_ABILITY
        item.controllerId shouldBe opponent
        item.name shouldBe "Raging Goblin"
    }

    test("stateDigest reflects every observable field of a stack item") {
        val env = newEnv()
        val me = env.playerIds[0]
        val opponent = env.playerIds[1]
        val base = ObservationBuilder(env.cardRegistry).build(env.state, me, env.legalActions())
            .observation as TrainingObservation

        val stackItem = StackItemView(
            entityId = EntityId.of("stack-item"),
            controllerId = me,
            name = "Visible Spell",
            kind = StackItemKind.SPELL,
            oracleText = "Visible rules text",
            targets = listOf(EntityId.of("target-a")),
        )
        val baseline = StateDigest.compute(base.copy(stack = listOf(stackItem)))

        listOf(
            stackItem.copy(controllerId = opponent),
            stackItem.copy(name = "Different Visible Spell"),
            stackItem.copy(kind = StackItemKind.TRIGGERED_ABILITY),
            stackItem.copy(oracleText = "Different visible rules text"),
            stackItem.copy(targets = listOf(EntityId.of("target-b"))),
        ).forEach { changed ->
            StateDigest.compute(base.copy(stack = listOf(changed))) shouldNotBe baseline
        }
    }

    test("stateDigest is unambiguous when a card name contains the encoding's delimiters") {
        val env = newEnv()
        val me = env.playerIds[0]
        val base = ObservationBuilder(env.cardRegistry).build(env.state, me, env.legalActions())
            .observation as TrainingObservation

        fun item(id: String, name: String) = StackItemView(
            entityId = EntityId.of(id),
            controllerId = null,
            name = name,
            kind = StackItemKind.SPELL,
        )

        // Card names are author-supplied, so a name can spell the digest's own field separators.
        // This one reproduces the two-item encoding exactly, and collides with it unless the
        // free-form fields are length-prefixed.
        val smuggled = item("s", "N:text=:targets=|S[1]=s2:ctl=null:kind=SPELL:name=M")
        val twoItems = listOf(item("s", "N"), item("s2", "M"))

        StateDigest.compute(base.copy(stack = listOf(smuggled))) shouldNotBe
            StateDigest.compute(base.copy(stack = twoItems))
    }

    test("stateDigest is stable for equivalent observations and changes when state changes") {
        val env = newEnv()
        val me = env.playerIds[0]

        val a = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation
        val b = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation
        a.stateDigest shouldBe b.stateDigest

        // Advance a step and verify the digest changes.
        val pass = env.legalActions().first { it.action is PassPriority }
        env.step(pass.action)
        val c = ObservationBuilder(cardRegistry = createRegistry()).build(env.state, me, env.legalActions()).observation
        c.stateDigest shouldNotBe a.stateDigest
    }

    test("structured delayed-trigger candidates survive observation, digest, and wire round-trip") {
        val env = newEnv()
        val chooser = env.playerIds[0]
        val firstTriggeringPlayer = env.playerIds[0]
        val secondTriggeringPlayer = env.playerIds[1]
        val decision = ChooseOptionDecision(
            id = "delayed-occurrence",
            playerId = chooser,
            prompt = "Choose a simultaneous occurrence",
            context = DecisionContext(phase = DecisionPhase.TRIGGER),
            options = listOf(
                "Trigger for player ${firstTriggeringPlayer.value}",
                "Trigger for player ${secondTriggeringPlayer.value}"
            ),
            optionMetadata = listOf(
                OptionMetadata(
                    id = firstTriggeringPlayer.value,
                    description = "Trigger for player ${firstTriggeringPlayer.value}",
                    triggeringPlayerId = firstTriggeringPlayer
                ),
                OptionMetadata(
                    id = secondTriggeringPlayer.value,
                    description = "Trigger for player ${secondTriggeringPlayer.value}",
                    triggeringPlayerId = secondTriggeringPlayer
                )
            )
        )
        // The engine holds a question only inside a Suspension (question + answer continuation);
        // this projection check presents it through ObservationBuilder's pendingDecisionOverride.
        val obs = ObservationBuilder(cardRegistry = createRegistry())
            .build(env.state, chooser, emptyList(), pendingDecisionOverride = decision)
            .observation as TrainingObservation

        val firstAction = obs.legalActions.first()
        firstAction.description shouldBe "Trigger for player ${firstTriggeringPlayer.value}"
        firstAction.actionSemantics!!["optionMetadata"]!!.jsonObject["triggeringPlayerId"]!!
            .jsonPrimitive.content shouldBe firstTriggeringPlayer.value

        val encoded = json.encodeToString(TrainingObservation.serializer(), obs)
        json.decodeFromString(TrainingObservation.serializer(), encoded) shouldBe obs

        val changed = obs.copy(
            legalActions = obs.legalActions.mapIndexed { index, action ->
                if (index == 0) {
                    val semantics = action.actionSemantics!!
                    val metadata = semantics["optionMetadata"]!!.jsonObject
                    action.copy(
                        actionSemantics = JsonObject(
                            semantics + (
                                "optionMetadata" to JsonObject(
                                    metadata + ("triggeringPlayerId" to JsonPrimitive(secondTriggeringPlayer.value))
                                )
                            )
                        )
                    )
                } else action
            }
        )
        StateDigest.compute(changed) shouldNotBe StateDigest.compute(obs)
    }
})
