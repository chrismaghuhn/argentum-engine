package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.UnlockRoomDoor
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gym.EpisodeClosureV1
import com.wingedsheep.gym.contract.ReplayFidelity as VerifiedReplayFidelity
import com.wingedsheep.gym.contract.ReplayVerificationBindingSource
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.engine.state.components.identity.RoomFaceId
import com.wingedsheep.sdk.model.CardEntry
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.PrintingRef
import com.wingedsheep.sdk.serialization.CardExporter
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.engine.state.YieldKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ReplayContentIdentityV1Test : ScenarioTestBase() {

    private val playerOne = EntityId("p1")
    private val playerTwo = EntityId("p2")

    private val pinA = card("Replay Identity Pin A") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Draw a card."
    }

    private val pinB = card("Replay Identity Pin B") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        oracleText = "Gain 1 life."
    }

    private val pinWithGeneratedAbility = card("Replay Identity Generated Ability Pin") {
        manaCost = "{0}"
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.DrawCards(1)
        }
    }

    private fun setup(
        seed: Long = 7L,
        startingPlayerIndex: Int? = 0,
        deck: Deck = Deck(
            cards = listOf("Forest", "Island"),
            cardEntries = listOf(CardEntry("Forest"), CardEntry("Island")),
            sideboard = listOf(CardEntry("Forest")),
        ),
    ) = ReplaySetup(
        seed = seed,
        format = Format.Standard,
        attackMode = AttackMode.MULTIPLE,
        startingHandSize = 0,
        skipMulligans = true,
        useHandSmoother = false,
        handSmootherCandidates = 3,
        startingPlayerIndex = startingPlayerIndex,
        teams = null,
        players = listOf(
            ReplayPlayerSetup(playerOne.value, "Alice", deck, startingLife = 20),
            ReplayPlayerSetup(playerTwo.value, "Bob", deck, startingLife = 20),
        ),
        seatRoster = emptyList(),
    )

    private fun replay(
        version: Int = CompactReplay.CURRENT_VERSION,
        setup: ReplaySetup = setup(),
        actions: List<GameAction> = emptyList(),
        yields: List<ReplayYieldEntry> = emptyList(),
        pinnedCards: List<String> = emptyList(),
        checkpoints: List<ReplayCheckpoint> = emptyList(),
    ) = CompactReplay(
        version = version,
        gameId = "replay-identity-game",
        players = listOf(
            ReplayPlayerInfo(playerOne.value, "Alice"),
            ReplayPlayerInfo(playerTwo.value, "Bob"),
        ),
        startedAt = "2026-01-01T00:00:00Z",
        endedAt = "2026-01-01T00:01:00Z",
        winnerName = "Alice",
        tournamentName = "Identity Tournament",
        tournamentRound = 3,
        setup = setup,
        actions = actions,
        yields = yields,
        engineVersion = "recorded-build",
        pinnedCards = pinnedCards,
        checkpoints = checkpoints,
    )

    private fun identity(replay: CompactReplay) = ReplayContentCanonicalizerV1.identity(replay)

    private fun exportedPinA(): String = CardExporter.exportToCompactJson(pinA)

    private fun exportedPinB(): String = CardExporter.exportToCompactJson(pinB)

    private fun exactZeroActionReplay(): CompactReplay {
        val base = replay()
        val initial = ReplayReconstructor(cardRegistry, null)
            .reconstructStateAt(base, 0)
            ?: error("Expected zero-action replay initial state")
        return base.copy(
            checkpoints = listOf(ReplayCheckpoint(0, ReplayFingerprint.of(initial, base.version))),
        )
    }

    private fun boundSource(replay: CompactReplay): ReplayVerificationBindingSource =
        GymReplayFrameSource(
            replay = replay,
            cardRegistry = cardRegistry,
            tailClosure = EpisodeClosureV1.Interrupted(
                stepCount = replay.actions.size,
                reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
            ),
        )

    init {
        test("semantic setup changes alter the replay content identity") {
            val base = identity(replay())

            identity(replay(setup = setup(seed = 8L))) shouldNotBe base
            identity(replay(setup = setup(startingPlayerIndex = 1))) shouldNotBe base
            identity(replay(setup = setup(deck = Deck(cards = listOf("Island", "Forest"))))) shouldNotBe base
        }

        test("sideboard order remains replay-relevant for allocated entity identities") {
            val forward = setup(
                deck = Deck(
                    cards = listOf("Forest", "Island"),
                    cardEntries = listOf(CardEntry("Forest"), CardEntry("Island")),
                    sideboard = listOf(CardEntry("Forest"), CardEntry("Island")),
                ),
            )
            val reverse = forward.copy(
                players = forward.players.map { player ->
                    player.copy(deck = player.deck.copy(sideboard = player.deck.sideboard.reversed()))
                },
            )

            identity(replay(setup = forward)) shouldNotBe identity(replay(setup = reverse))
        }

        test("ignored deck compatibility fields do not alter effective initialization identity") {
            val base = setup()
            val variant = base.copy(
                players = base.players.map { player ->
                    player.copy(
                        commanderCardName = "Unused player commander field",
                        deck = player.deck.copy(
                            cards = listOf("Unrelated legacy card"),
                            commander = "Unused deck commander field",
                            commanderPrinting = PrintingRef("UNUSED", "1"),
                        ),
                    )
                },
            )

            identity(replay(setup = base)) shouldBe identity(replay(setup = variant))
        }

        test("commander printing alters identity when commander setup consumes it") {
            val base = setup().copy(
                format = Format.Commander(),
                players = setup().players.map { player ->
                    player.copy(
                        commanderCardName = pinA.name,
                        deck = player.deck.copy(commanderPrinting = PrintingRef("CMD", "1")),
                    )
                },
            )
            val changed = base.copy(
                players = base.players.map { player ->
                    player.copy(
                        deck = player.deck.copy(commanderPrinting = PrintingRef("CMD", "2")),
                    )
                },
            )

            identity(replay(setup = base)) shouldNotBe identity(replay(setup = changed))
        }

        test("ordered semantic actions alter the replay content identity") {
            val first = PassPriority(playerOne)
            val second = PassPriority(playerTwo)

            identity(replay(actions = listOf(first))) shouldNotBe identity(replay())
            identity(replay(actions = listOf(first))) shouldNotBe
                identity(replay(actions = listOf(second)))
            identity(replay(actions = listOf(first, second))) shouldNotBe
                identity(replay(actions = listOf(second, first)))
        }

        test("semantic yields alter the replay content identity") {
            val clearAll = ReplayYieldEntry(0, playerOne.value, ReplayYieldOp.CLEAR_ALL)
            val clearAbility = ReplayYieldEntry(
                afterActionCount = 0,
                playerId = playerOne.value,
                op = ReplayYieldOp.CLEAR_ABILITY,
                identity = AbilityIdentity("Replay Identity Pin A", AbilityId("printed-yield")),
            )

            identity(replay(yields = listOf(clearAll))) shouldNotBe identity(replay())
            identity(replay(yields = listOf(clearAll))) shouldNotBe
                identity(replay(yields = listOf(clearAbility)))
        }

        test("actual pinned card content alters the replay content identity") {
            val original = exportedPinA()
            val changed = CardExporter.exportToCompactJson(pinA.copy(oracleText = "Draw two cards."))

            identity(replay(pinnedCards = listOf(original))) shouldNotBe
                identity(replay(pinnedCards = listOf(changed)))
        }

        test("unique pinned definitions are canonicalized by stable card identity") {
            val forward = identity(replay(pinnedCards = listOf(exportedPinA(), exportedPinB())))
            val reverse = identity(replay(pinnedCards = listOf(exportedPinB(), exportedPinA())))

            forward shouldBe reverse
        }

        test("ambiguous same-name pinned variants fail closed") {
            val first = CardExporter.exportToCompactJson(
                pinA.copy(setCode = "SET-A", metadata = pinA.metadata.copy(collectorNumber = "1")),
            )
            val second = CardExporter.exportToCompactJson(
                pinA.copy(setCode = "SET-B", metadata = pinA.metadata.copy(collectorNumber = "2")),
            )

            shouldThrow<IllegalArgumentException> {
                identity(replay(pinnedCards = listOf(first, second)))
            }
        }

        test("presentation and proof metadata do not alter replay content identity") {
            val base = replay(
                checkpoints = listOf(ReplayCheckpoint(0, "a".repeat(64))),
            )
            val variants = listOf(
                base.copy(gameId = "different-game"),
                base.copy(players = listOf(ReplayPlayerInfo("other-1", "Other"))),
                base.copy(startedAt = "2030-01-01T00:00:00Z", endedAt = "2030-01-01T00:01:00Z"),
                base.copy(winnerName = null, tournamentName = null, tournamentRound = null),
                base.copy(engineVersion = "other-build"),
                base.copy(
                    setup = base.setup.copy(
                        seatRoster = listOf(ServerMessage.PlayerSeatInfo("p1", "Alice", 0)),
                    ),
                ),
                base.copy(checkpoints = listOf(ReplayCheckpoint(0, "b".repeat(64)))),
            )

            variants.forEach { variant -> identity(variant) shouldBe identity(base) }
        }

        test("decision response routing nonces do not alter replay content identity") {
            fun action(nonce: String) = SubmitDecision(
                playerId = playerOne,
                response = YesNoResponse(nonce, choice = true),
            )

            identity(replay(actions = listOf(action("nonce-a")))) shouldBe
                identity(replay(actions = listOf(action("nonce-b"))))
        }

        test("decode-only combat response ordering fields do not alter replay content identity") {
            fun action(
                orderedBlockers: Map<EntityId, List<EntityId>>,
                orderedAttackers: Map<EntityId, List<EntityId>>,
            ) = SubmitDecision(
                playerId = playerOne,
                response = CombatResolutionResponse(
                    decisionId = "nonce",
                    edges = listOf(DamageEdgeAmount("edge", 1)),
                    orderedBlockers = orderedBlockers,
                    orderedAttackers = orderedAttackers,
                ),
            )

            val first = action(
                orderedBlockers = mapOf(EntityId("attacker-a") to listOf(EntityId("blocker-a"))),
                orderedAttackers = mapOf(EntityId("attacker-a") to listOf(EntityId("blocker-a"))),
            )
            val second = action(
                orderedBlockers = mapOf(EntityId("attacker-b") to listOf(EntityId("blocker-b"))),
                orderedAttackers = mapOf(EntityId("attacker-b") to listOf(EntityId("blocker-b"))),
            )

            identity(replay(actions = listOf(first))) shouldBe identity(replay(actions = listOf(second)))
            val preimage = ReplayContentCanonicalizerV1.canonicalPreimage(replay(actions = listOf(first)))
            preimage.contains("orderedBlockers") shouldBe false
            preimage.contains("orderedAttackers") shouldBe false
        }

        test("unanchored generated ability handles in actions and yields fail closed") {
            fun action(abilityId: String) = ActivateAbility(
                playerId = playerOne,
                sourceId = EntityId("source"),
                abilityId = AbilityId(abilityId),
            )

            listOf("ability_101", "ability_909").forEach { generatedId ->
                shouldThrow<IllegalArgumentException> {
                    identity(replay(actions = listOf(action(generatedId))))
                }
                shouldThrow<IllegalArgumentException> {
                    identity(
                        replay(
                            yields = listOf(
                                ReplayYieldEntry(
                                    afterActionCount = 0,
                                    playerId = playerOne.value,
                                    op = ReplayYieldOp.CLEAR_ABILITY,
                                    identity = AbilityIdentity(pinA.name, AbilityId(generatedId)),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        test("stable explicit ability IDs remain semantic") {
            fun action(abilityId: String) = ActivateAbility(
                playerId = playerOne,
                sourceId = EntityId("source"),
                abilityId = AbilityId(abilityId),
            )

            identity(replay(actions = listOf(action("printed-a")))) shouldNotBe
                identity(replay(actions = listOf(action("printed-b"))))
        }

        test("generated ability aliases reserve stable IDs") {
            val generatedPinId = pinWithGeneratedAbility.script.activatedAbilities.single().id.value

            fun replayWith(generatedId: String): CompactReplay {
                fun action(abilityId: String) = ActivateAbility(
                    playerId = playerOne,
                    sourceId = EntityId("source"),
                    abilityId = AbilityId(abilityId),
                )
                val pin = pinWithGeneratedAbility.copy(
                    script = pinWithGeneratedAbility.script.copy(
                        activatedAbilities = pinWithGeneratedAbility.script.activatedAbilities.map {
                            it.copy(id = AbilityId(generatedId))
                        },
                    ),
                )
                return replay(
                    actions = listOf(action("A0"), action(generatedId)),
                    pinnedCards = listOf(CardExporter.exportToCompactJson(pin)),
                )
            }

            identity(replayWith(generatedPinId)) shouldBe identity(replayWith("ability_909"))
            val preimage = ReplayContentCanonicalizerV1.canonicalPreimage(replayWith(generatedPinId))
            preimage.contains("\"abilityId\":\"A0\"") shouldBe true
            preimage.contains("\"abilityId\":\"A1\"") shouldBe true
        }

        test("generated ability handles anchored by multiple pinned definitions fail closed") {
            val generatedId = pinWithGeneratedAbility.script.activatedAbilities.single().id.value
            val otherPin = pinWithGeneratedAbility.copy(name = "Replay Identity Other Generated Pin")
            val action = ActivateAbility(
                playerId = playerOne,
                sourceId = EntityId("source"),
                abilityId = AbilityId(generatedId),
            )

            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        actions = listOf(action),
                        pinnedCards = listOf(
                            CardExporter.exportToCompactJson(pinWithGeneratedAbility),
                            CardExporter.exportToCompactJson(otherPin),
                        ),
                    ),
                )
            }
        }

        test("generated ability references shared by pinned actions and yields are normalized together") {
            val generatedPinId = pinWithGeneratedAbility.script.activatedAbilities.single().id.value
            val equivalentPin = pinWithGeneratedAbility.copy(
                script = pinWithGeneratedAbility.script.copy(
                    activatedAbilities = pinWithGeneratedAbility.script.activatedAbilities.map {
                        it.copy(id = AbilityId("ability_909"))
                    },
                ),
            )
            val firstYield = ReplayYieldEntry(
                afterActionCount = 0,
                playerId = playerOne.value,
                op = ReplayYieldOp.SET,
                identity = AbilityIdentity(
                    pinWithGeneratedAbility.name,
                    AbilityId(generatedPinId),
                ),
                kind = YieldKind.ALWAYS_ANSWER_YES,
            )
            val secondYield = firstYield.copy(
                identity = firstYield.identity!!.copy(abilityId = AbilityId("ability_909")),
            )

            val first = replay(
                actions = listOf(
                    ActivateAbility(
                        playerId = playerOne,
                        sourceId = EntityId("source"),
                        abilityId = AbilityId(generatedPinId),
                    ),
                ),
                yields = listOf(firstYield),
                pinnedCards = listOf(CardExporter.exportToCompactJson(pinWithGeneratedAbility)),
            )
            val second = replay(
                actions = listOf(
                    ActivateAbility(
                        playerId = playerOne,
                        sourceId = EntityId("source"),
                        abilityId = AbilityId("ability_909"),
                    ),
                ),
                yields = listOf(secondYield),
                pinnedCards = listOf(CardExporter.exportToCompactJson(equivalentPin)),
            )

            identity(first) shouldBe identity(second)
            ReplayContentCanonicalizerV1.canonicalPreimage(first)
                .contains(generatedPinId) shouldBe false
            ReplayContentCanonicalizerV1.canonicalPreimage(second)
                .contains("ability_909") shouldBe false
        }

        test("typed map iteration order does not alter replay content identity") {
            val forward = DeclareAttackers(
                playerId = playerOne,
                attackers = linkedMapOf(
                    EntityId("attacker-a") to playerTwo,
                    EntityId("attacker-b") to playerTwo,
                ),
            )
            val reverse = DeclareAttackers(
                playerId = playerOne,
                attackers = linkedMapOf(
                    EntityId("attacker-b") to playerTwo,
                    EntityId("attacker-a") to playerTwo,
                ),
            )

            identity(replay(actions = listOf(forward))) shouldBe identity(replay(actions = listOf(reverse)))
        }

        test("typed set iteration order does not alter replay content identity") {
            fun declaration(band: Set<EntityId>) = DeclareAttackers(
                playerId = playerOne,
                attackers = linkedMapOf(
                    EntityId("attacker-a") to playerTwo,
                    EntityId("attacker-b") to playerTwo,
                ),
                bands = listOf(band),
            )

            identity(
                replay(actions = listOf(declaration(linkedSetOf(EntityId("attacker-a"), EntityId("attacker-b"))))),
            ) shouldBe identity(
                replay(actions = listOf(declaration(linkedSetOf(EntityId("attacker-b"), EntityId("attacker-a"))))),
            )
        }

        test("malformed pins and unsupported replay versions fail closed") {
            shouldThrow<IllegalArgumentException> {
                identity(replay(pinnedCards = listOf("{}")))
            }
            shouldThrow<IllegalArgumentException> {
                identity(replay(pinnedCards = listOf(exportedPinA(), exportedPinA())))
            }
            shouldThrow<IllegalArgumentException> {
                identity(replay(version = CompactReplay.CURRENT_VERSION + 1))
            }
        }

        test("malformed typed replay yields fail closed") {
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        yields = listOf(
                            ReplayYieldEntry(
                                afterActionCount = -1,
                                playerId = playerOne.value,
                                op = ReplayYieldOp.CLEAR_ALL,
                            ),
                        ),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        yields = listOf(
                            ReplayYieldEntry(
                                afterActionCount = 0,
                                playerId = playerOne.value,
                                op = ReplayYieldOp.SET,
                            ),
                        ),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        yields = listOf(
                            ReplayYieldEntry(
                                afterActionCount = 0,
                                playerId = "unknown-player",
                                op = ReplayYieldOp.CLEAR_ALL,
                            ),
                        ),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        setup = setup(startingPlayerIndex = 2),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        actions = listOf(
                            ActivateAbility(
                                playerId = playerOne,
                                sourceId = EntityId("source"),
                                abilityId = AbilityId(""),
                            ),
                        ),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        actions = listOf(
                            UnlockRoomDoor(
                                playerId = playerOne,
                                roomId = EntityId("room"),
                                faceId = RoomFaceId(""),
                            ),
                        ),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                identity(
                    replay(
                        yields = listOf(
                            ReplayYieldEntry(
                                afterActionCount = 0,
                                playerId = playerOne.value,
                                op = ReplayYieldOp.CLEAR_ABILITY,
                                identity = AbilityIdentity("", AbilityId("stable")),
                            ),
                        ),
                    ),
                )
            }
        }

        test("canonical preimage contains content but excludes proof and presentation fields") {
            val preimage = ReplayContentCanonicalizerV1.canonicalPreimage(
                replay(
                    actions = listOf(PassPriority(playerOne)),
                    checkpoints = listOf(ReplayCheckpoint(1, "a".repeat(64))),
                ),
            )

            preimage.contains("\"replayVersion\"").shouldBe(true)
            preimage.contains("\"setup\"").shouldBe(true)
            preimage.contains("\"actions\"").shouldBe(true)
            preimage.contains("\"yields\"").shouldBe(true)
            preimage.contains("\"pinnedCards\"").shouldBe(true)
            preimage.contains("checkpoints").shouldBe(false)
            preimage.contains("gameId").shouldBe(false)
            preimage.contains("tailClosure").shouldBe(false)
        }

        test("identity is stable across CompactReplay codec round-trip") {
            val original = replay(actions = listOf(SubmitDecision(playerOne, YesNoResponse("nonce", true))))
            val decoded = ReplayCodec.decode(ReplayCodec.encode(original))

            identity(decoded) shouldBe identity(original)
        }

        test("bound A4 verification carries the content identity of its exact replay") {
            val replay = exactZeroActionReplay()
            val source = boundSource(replay)

            val binding = source.verifyBinding()

            binding.replayContentIdentity shouldBe identity(replay)
            binding.replayContentIdentity.replayVersion shouldBe binding.verification.replayVersion
            binding.verification.fidelity shouldBe VerifiedReplayFidelity.EXACT
            binding.verification.completeRangeVerified shouldBe true
        }

        test("checkpoint proof mutation leaves content identity unchanged but changes A4 evidence") {
            val replay = exactZeroActionReplay()
            val mutated = replay.copy(
                checkpoints = listOf(ReplayCheckpoint(0, "0".repeat(64))),
            )

            val originalBinding = boundSource(replay).verifyBinding()
            val mutatedBinding = boundSource(mutated).verifyBinding()

            mutatedBinding.replayContentIdentity shouldBe originalBinding.replayContentIdentity
            mutatedBinding.verification.fidelity shouldBe VerifiedReplayFidelity.DIVERGED
            mutatedBinding.verification.completeRangeVerified shouldBe false
        }

        test("binding uses the unchanged existing A4 verification result") {
            val replay = exactZeroActionReplay()

            val existing = GymReplayFrameSource(
                replay = replay,
                cardRegistry = cardRegistry,
                tailClosure = EpisodeClosureV1.Interrupted(
                    stepCount = 0,
                    reason = com.wingedsheep.gym.EpisodeInterruptionReason.HORIZON_REACHED,
                ),
            ).verify()
            val bound = boundSource(replay).verifyBinding().verification

            bound shouldBe existing
        }
    }
}
