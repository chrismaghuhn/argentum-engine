package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.FloatingManaBucketKeyV1
import com.wingedsheep.engine.core.BatchYesNoDecision
import com.wingedsheep.engine.core.BudgetModalDecision
import com.wingedsheep.engine.core.BudgetModeOption
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseReplacementDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.ConditionalSelectionMinimum
import com.wingedsheep.engine.core.LegendRuleContinuation
import com.wingedsheep.engine.core.LeylineDecisionContinuation
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ModeOption
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.PendingDecision
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.WaterbendPermanentChoice
import com.wingedsheep.engine.core.suspendForDecision
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.PlayerYields
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.event.GrantedActivatedAbility
import com.wingedsheep.engine.event.GrantedTriggeredAbility
import com.wingedsheep.engine.state.components.battlefield.AbilityActivatedThisTurnComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.engine.state.components.stack.AbilityOnStackComponent
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.AbilityIdentity
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.effects.Effect
import com.wingedsheep.sdk.scripting.effects.Gate
import com.wingedsheep.sdk.scripting.effects.GatedEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.GameRng
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.jsonObject

class ReplayFingerprintV3Test : FunSpec({

    fun decision(
        id: String,
        sourceId: EntityId? = null,
        prompt: String = "Choose whether to continue",
        effectHint: String? = null,
    ) = YesNoDecision(
        id = id,
        playerId = EntityId("p1"),
        prompt = prompt,
        context = DecisionContext(sourceId = sourceId, effectHint = effectHint),
    )

    // A question is no longer a GameState field: the engine installs it as one Suspension
    // (question + the answer continuation that consumes it) on top of the continuation stack, with
    // its id allocated from GameState.nextRoutingId. Every compared pair below shares this neutral
    // answer, so only the question differs.
    val neutralAnswer = LeylineDecisionContinuation(EntityId("p1"), EntityId("leyline"), "Test leyline")

    // The routing allocator position is fingerprinted state of its own. Every fixture is parked at
    // the same position after asking, so a pair differs only in what the test varies.
    val parkedRoutingId = 100L

    /**
     * Ask [question] from routing allocator position [routingId] (the question's id becomes
     * "r<routingId>"), then park the allocator at [parkedRoutingId].
     */
    fun pending(routingId: Long = 0L, question: (String) -> PendingDecision): GameState =
        GameState(nextRoutingId = routingId)
            .suspendForDecision(question = question, answer = neutralAnswer)
            .state
            .copy(nextRoutingId = parkedRoutingId)

    test("v3 fingerprint is a complete 64-hex SHA-256 value") {
        val fingerprint = ReplayFingerprint.of(GameState())

        fingerprint.length shouldBe 64
        fingerprint.matches(Regex("[0-9a-f]{64}")).shouldBeTrue()
    }

    test("version dispatch preserves legacy v1/v2 semantics") {
        val state = GameState(turnNumber = 4, nextEntityId = 3L)

        ReplayFingerprint.of(state, 1) shouldBe ReplayFingerprint.of(state, 2)
        ReplayFingerprint.of(state, 1).length shouldBe 16
        ReplayFingerprint.of(state, 3) shouldNotBe ReplayFingerprint.of(state, 1)
        shouldThrow<UnsupportedReplayVersionException> {
            ReplayFingerprint.of(state, CompactReplay.CURRENT_VERSION + 1)
        }
    }

    test("v3 fingerprint includes RNG, next entity id, and ordered library state") {
        val library = ZoneKey(EntityId("p1"), Zone.LIBRARY)
        val base = GameState(
            zones = mapOf(library to listOf(EntityId("e1"), EntityId("e2"))),
            rng = GameRng(7L),
            nextEntityId = 2L,
        )

        ReplayFingerprint.of(base.copy(rng = GameRng(8L))) shouldNotBe ReplayFingerprint.of(base)
        ReplayFingerprint.of(base.copy(nextEntityId = 3L)) shouldNotBe ReplayFingerprint.of(base)
        ReplayFingerprint.of(
            base.copy(zones = mapOf(library to listOf(EntityId("e2"), EntityId("e1"))))
        ) shouldNotBe ReplayFingerprint.of(base)
    }

    test("v3 semantic state fingerprint binds source-color floating provenance") {
        val player = EntityId("p1")
        val firstSource = EntityId("e108")
        val secondSource = EntityId("e117")

        fun state(detail: Map<EntityId, Map<PaymentManaColor, Int>>) = GameState().withEntity(
            player,
            ComponentContainer.of(
                ManaPoolComponent(
                    black = 1,
                    green = 1,
                    manaBySource = linkedMapOf(firstSource to 1, secondSource to 1),
                    manaBySourceAndColor = detail,
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                ),
            ),
        )

        val blackThenGreen = state(
            linkedMapOf(
                firstSource to mapOf(PaymentManaColor.BLACK to 1),
                secondSource to mapOf(PaymentManaColor.GREEN to 1),
            ),
        )
        val greenThenBlack = state(
            linkedMapOf(
                firstSource to mapOf(PaymentManaColor.GREEN to 1),
                secondSource to mapOf(PaymentManaColor.BLACK to 1),
            ),
        )
        val sameContentDifferentOrder = state(
            linkedMapOf(
                secondSource to mapOf(PaymentManaColor.GREEN to 1),
                firstSource to mapOf(PaymentManaColor.BLACK to 1),
            ),
        )

        ReplayFingerprint.of(blackThenGreen, 3) shouldNotBe ReplayFingerprint.of(greenThenBlack, 3)
        ReplayFingerprint.of(blackThenGreen, 3) shouldBe
            ReplayFingerprint.of(sameContentDifferentOrder, 3)
    }

    test("v4 fingerprint binds joint bucket provenance while v3 keeps its historical shape") {
        val player = EntityId("p1")
        val source = EntityId("e108")
        val base = GameState().withEntity(
            player,
            ComponentContainer.of(
                ManaPoolComponent(
                    green = 1,
                    manaBySource = mapOf(source to 1),
                    manaBySourceAndColor = mapOf(source to mapOf(PaymentManaColor.GREEN to 1)),
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                ),
            ),
        )
        val joint = base.updateEntity(player) { container ->
            container.with(
                container.get<ManaPoolComponent>()!!.copy(
                    manaByFloatingBucket = mapOf(
                        FloatingManaBucketKeyV1(
                            source,
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 1,
                    ),
                    manaProvenanceKnownTo = setOf(player),
                ),
            )
        }

        ReplayFingerprint.of(base, 3) shouldBe ReplayFingerprint.of(joint, 3)
        ReplayFingerprint.of(base, 4) shouldNotBe ReplayFingerprint.of(joint, 4)
    }

    test("v5 and v6 payment replay carriers retain the v4 fingerprint semantics") {
        val player = EntityId("p1")
        val source = EntityId("e108")
        val state = GameState().withEntity(
            player,
            ComponentContainer.of(
                ManaPoolComponent(
                    green = 1,
                    manaBySource = mapOf(source to 1),
                    manaBySourceAndColor = mapOf(source to mapOf(PaymentManaColor.GREEN to 1)),
                    manaProvenanceCompleteness = ManaProvenanceCompleteness.COMPLETE,
                    manaByFloatingBucket = mapOf(
                        FloatingManaBucketKeyV1(
                            source,
                            PaymentManaColor.GREEN,
                            setOf(Subtype.FOREST),
                        ) to 1,
                    ),
                    manaProvenanceKnownTo = setOf(player),
                ),
            ),
        )

        ReplayFingerprint.of(state, 5) shouldBe ReplayFingerprint.of(state, 4)
        ReplayFingerprint.of(state, 6) shouldBe ReplayFingerprint.of(state, 4)
    }

    test("v3 fingerprint includes semantic yields and canonicalizes unordered sets") {
        val player = EntityId("p1")
        val identity = AbilityIdentity("Test Card#TST-1", AbilityId("test-ability"))
        val secondIdentity = AbilityIdentity("Second Card#TST-2", AbilityId("second-ability"))
        val yielded = GameState(
            priorityPassedBy = linkedSetOf(EntityId("p2"), EntityId("p1")),
            yieldsByPlayer = mapOf(
                player to PlayerYields(
                    untilEndOfTurn = linkedSetOf(identity, secondIdentity),
                    wholeGame = linkedSetOf(secondIdentity, identity),
                    autoAnswer = mapOf(identity to true),
                ),
            ),
        )
        val reordered = yielded.copy(
            priorityPassedBy = linkedSetOf(EntityId("p1"), EntityId("p2")),
            yieldsByPlayer = linkedMapOf(
                player to PlayerYields(
                    untilEndOfTurn = linkedSetOf(secondIdentity, identity),
                    wholeGame = linkedSetOf(identity, secondIdentity),
                    autoAnswer = mapOf(identity to true),
                ),
            ),
        )

        ReplayFingerprint.of(reordered, 3) shouldBe ReplayFingerprint.of(yielded, 3)
        ReplayFingerprint.of(
            yielded.copy(yieldsByPlayer = mapOf(player to PlayerYields(autoAnswer = mapOf(identity to false))))
        ) shouldNotBe ReplayFingerprint.of(yielded, 3)
    }

    test("v3 fingerprint canonicalizes structured map iteration order") {
        val p1Library = ZoneKey(EntityId("p1"), Zone.LIBRARY)
        val p2Library = ZoneKey(EntityId("p2"), Zone.LIBRARY)
        val first = GameState(
            zones = linkedMapOf(
                p1Library to listOf(EntityId("p1-card")),
                p2Library to listOf(EntityId("p2-card")),
            ),
        )
        val reordered = first.copy(
            zones = linkedMapOf(
                p2Library to listOf(EntityId("p2-card")),
                p1Library to listOf(EntityId("p1-card")),
            ),
        )

        ReplayFingerprint.of(first, 3) shouldBe ReplayFingerprint.of(reordered, 3)
    }

    test("v3 ignores allocation-order generated AbilityId handles but preserves ability semantics") {
        fun stateWithGrantedAbility(id: String, effect: Effect = Effects.DrawCards(1)) =
            GameState(
                grantedActivatedAbilities = listOf(
                    GrantedActivatedAbility(
                        entityId = EntityId("e1"),
                        ability = ActivatedAbility(
                            id = AbilityId(id),
                            cost = Costs.Free,
                            effect = effect,
                        ),
                        duration = Duration.Permanent,
                    ),
                ),
            )

        ReplayFingerprint.of(stateWithGrantedAbility("ability_123"), 3) shouldBe
            ReplayFingerprint.of(stateWithGrantedAbility("ability_987"), 3)

        ReplayFingerprint.of(stateWithGrantedAbility("ability_123")) shouldNotBe
            ReplayFingerprint.of(stateWithGrantedAbility("ability_987", Effects.GainLife(3)), 3)
        ReplayFingerprint.of(stateWithGrantedAbility("printed_draw"), 3) shouldNotBe
            ReplayFingerprint.of(stateWithGrantedAbility("printed_gain", Effects.GainLife(3)), 3)
    }

    test("v3 aliases every serialized AbilityId path through one shared relation table") {
        fun stateWithHandle(handle: String) = GameState(
            entities = mapOf(
                EntityId("e1") to ComponentContainer.of(
                    AbilityActivatedThisTurnComponent(
                        abilityIds = setOf(AbilityId(handle)),
                        activationCounts = mapOf(AbilityId(handle) to 1),
                    ),
                ),
            ),
            grantedActivatedAbilities = listOf(
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(handle),
                        cost = Costs.Free,
                        effect = Effects.DrawCards(1),
                    ),
                    duration = Duration.Permanent,
                ),
            ),
            yieldsByPlayer = mapOf(
                EntityId("p1") to PlayerYields(
                    wholeGame = setOf(AbilityIdentity("Card#1", AbilityId(handle))),
                ),
            ),
        )

        val stateA = stateWithHandle("ability_123")
        val stateB = stateWithHandle("ability_987")
        ReplayFingerprint.of(stateA, 3) shouldBe ReplayFingerprint.of(stateB, 3)

        val canonical = TransitionSemanticGameStateCanonicalizer.canonicalJson(stateA)
        canonical.contains("ability_123").shouldBeFalse()
        canonical shouldContain "\"A0\""

        fun stateWithCounts(entries: LinkedHashMap<AbilityId, Int>) = GameState(
            entities = mapOf(
                EntityId("e1") to ComponentContainer.of(
                    AbilityActivatedThisTurnComponent(
                        abilityIds = entries.keys,
                        activationCounts = entries,
                    ),
                ),
            ),
        )
        val countsA = stateWithCounts(
            linkedMapOf(AbilityId("ability_1") to 1, AbilityId("ability_2") to 2)
        )
        val countsB = stateWithCounts(
            linkedMapOf(AbilityId("ability_2") to 2, AbilityId("ability_1") to 1)
        )
        ReplayFingerprint.of(countsA, 3) shouldBe ReplayFingerprint.of(countsB, 3)
    }

    test("v3 keeps distinct generated ability ordinals and structures distinguishable") {
        fun state(firstId: String, secondId: String, reverseEffects: Boolean = false) = GameState(
            grantedActivatedAbilities = listOf(
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(firstId),
                        cost = Costs.Free,
                        effect = if (reverseEffects) Effects.GainLife(3) else Effects.DrawCards(1),
                    ),
                    duration = Duration.Permanent,
                ),
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(secondId),
                        cost = Costs.Free,
                        effect = if (reverseEffects) Effects.DrawCards(1) else Effects.GainLife(3),
                    ),
                    duration = Duration.Permanent,
                ),
            ),
        )

        ReplayFingerprint.of(state("ability_101", "ability_102"), 3) shouldBe
            ReplayFingerprint.of(state("ability_201", "ability_202"), 3)
        ReplayFingerprint.of(state("ability_101", "ability_102"), 3) shouldBe
            ReplayFingerprint.of(state("ability_202", "ability_201"), 3)
        ReplayFingerprint.of(state("ability_101", "ability_102"), 3) shouldNotBe
            ReplayFingerprint.of(state("ability_201", "ability_202", reverseEffects = true), 3)
    }

    test("v3 keeps stable AbilityIds distinct from generated aliases") {
        fun state(stableId: String, generatedId: String, reverseEffects: Boolean = false) = GameState(
            grantedActivatedAbilities = listOf(
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(stableId),
                        cost = Costs.Free,
                        effect = if (reverseEffects) Effects.GainLife(3) else Effects.DrawCards(1),
                    ),
                    duration = Duration.Permanent,
                ),
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(generatedId),
                        cost = Costs.Free,
                        effect = if (reverseEffects) Effects.DrawCards(1) else Effects.GainLife(3),
                    ),
                    duration = Duration.Permanent,
                ),
            ),
        )

        ReplayFingerprint.of(state("A0", "ability_123"), 3) shouldNotBe
            ReplayFingerprint.of(state("A0", "ability_987", reverseEffects = true), 3)
    }

    test("v3 ignores unordered tracking insertion order when generated ability shapes tie") {
        fun state(firstId: String, secondId: String, reverseTrackingOrder: Boolean) = GameState(
            entities = mapOf(
                EntityId("e1") to ComponentContainer.of(
                    AbilityActivatedThisTurnComponent(
                        abilityIds = if (reverseTrackingOrder) {
                            linkedSetOf(AbilityId(secondId), AbilityId(firstId))
                        } else {
                            linkedSetOf(AbilityId(firstId), AbilityId(secondId))
                        },
                        activationCounts = if (reverseTrackingOrder) {
                            linkedMapOf(AbilityId(secondId) to 1, AbilityId(firstId) to 1)
                        } else {
                            linkedMapOf(AbilityId(firstId) to 1, AbilityId(secondId) to 1)
                        },
                    ),
                ),
            ),
            grantedActivatedAbilities = listOf(
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(firstId),
                        cost = Costs.Free,
                        effect = Effects.DrawCards(1),
                    ),
                    duration = Duration.Permanent,
                ),
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(secondId),
                        cost = Costs.Free,
                        effect = Effects.GainLife(3),
                    ),
                    duration = Duration.Permanent,
                ),
            ),
        )

        ReplayFingerprint.of(state("ability_401", "ability_402", reverseTrackingOrder = false), 3) shouldBe
            ReplayFingerprint.of(state("ability_901", "ability_902", reverseTrackingOrder = true), 3)
    }

    test("v3 sorts structured auto-answer map pairs after global ability aliasing") {
        fun state(
            firstId: String,
            secondId: String,
            reverseAutoAnswerOrder: Boolean = false,
            reverseEffects: Boolean = false,
        ) = GameState(
            grantedActivatedAbilities = listOf(
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(firstId),
                        cost = Costs.Free,
                        effect = if (reverseEffects) Effects.GainLife(3) else Effects.DrawCards(1),
                    ),
                    duration = Duration.Permanent,
                ),
                GrantedActivatedAbility(
                    entityId = EntityId("e1"),
                    ability = ActivatedAbility(
                        id = AbilityId(secondId),
                        cost = Costs.Free,
                        effect = if (reverseEffects) Effects.DrawCards(1) else Effects.GainLife(3),
                    ),
                    duration = Duration.Permanent,
                ),
            ),
            yieldsByPlayer = mapOf(
                EntityId("p1") to PlayerYields(
                    autoAnswer = if (reverseAutoAnswerOrder) {
                        linkedMapOf(
                            AbilityIdentity("Card#1", AbilityId(secondId)) to true,
                            AbilityIdentity("Card#1", AbilityId(firstId)) to true,
                        )
                    } else {
                        linkedMapOf(
                            AbilityIdentity("Card#1", AbilityId(firstId)) to true,
                            AbilityIdentity("Card#1", AbilityId(secondId)) to true,
                        )
                    },
                ),
            ),
        )

        ReplayFingerprint.of(state("ability_501", "ability_502"), 3) shouldBe
            ReplayFingerprint.of(
                state("ability_901", "ability_902", reverseAutoAnswerOrder = true),
                3,
            )
        ReplayFingerprint.of(state("ability_501", "ability_502"), 3) shouldNotBe
            ReplayFingerprint.of(state("ability_501", "ability_502", reverseEffects = true), 3)
    }

    test("v3 aliases nested triggered, stack, and effect ability references") {
        fun stateWithHandle(handle: String) = GameState(
            entities = mapOf(
                EntityId("e1") to ComponentContainer.of(
                    AbilityOnStackComponent(
                        sourceId = EntityId("e1"),
                        controllerId = EntityId("p1"),
                        abilityId = AbilityId(handle),
                        effect = GatedEffect(
                            gate = Gate.OnceEachTurn(AbilityId(handle)),
                            then = Effects.DrawCards(1),
                        ),
                    ),
                ),
            ),
            grantedTriggeredAbilities = listOf(
                GrantedTriggeredAbility(
                    entityId = EntityId("e1"),
                    ability = TriggeredAbility(
                        id = AbilityId(handle),
                        trigger = EventPattern.StepEvent(Step.END, Player.You),
                        effect = Effects.DrawCards(1),
                    ),
                    duration = Duration.Permanent,
                ),
            ),
        )

        val stateA = stateWithHandle("ability_301")
        val stateB = stateWithHandle("ability_999")
        ReplayFingerprint.of(stateA, 3) shouldBe ReplayFingerprint.of(stateB, 3)
    }

    test("nonce changes are ignored but decision semantics remain fingerprinted") {
        // Same question asked from two routing allocator positions: the routing ids differ
        // ("r0" vs "r7"), the allocator is parked at the same position, the semantics match.
        val withAbc = pending(routingId = 0L) { id -> decision(id) }
        val withXyz = pending(routingId = 7L) { id -> decision(id) }
        val differentSemantics = pending(routingId = 7L) { id ->
            decision(id, sourceId = EntityId("source"))
        }
        withAbc.pendingDecision!!.id shouldNotBe withXyz.pendingDecision!!.id
        withAbc.nextRoutingId shouldBe withXyz.nextRoutingId

        ReplayFingerprint.of(withAbc) shouldBe ReplayFingerprint.of(withXyz)
        // Same routing id as withXyz, so only the decision semantics differ.
        ReplayFingerprint.of(withXyz) shouldNotBe ReplayFingerprint.of(differentSemantics)

        ReplayFingerprint.of(
            pending(routingId = 0L) { id -> decision(id, prompt = "Prompt A", effectHint = "Hint A") }
        ) shouldBe ReplayFingerprint.of(
            pending(routingId = 7L) { id -> decision(id, prompt = "Prompt B", effectHint = "Hint B") }
        )
    }

    test("v3 excludes audited presentation-only decision labels") {
        // Each A/B pair is asked from the same routing allocator position, so only the labels differ;
        // routing-id (nonce) invariance is asserted by "nonce changes are ignored ..." above.
        val yesNoA = pending { id ->
            YesNoDecision(
                id = id,
                playerId = EntityId("p1"),
                prompt = "same semantic question",
                context = DecisionContext(),
                yesText = "Accept the offer",
                noText = "Decline the offer",
                hint = "A very specific UI hint",
            )
        }
        val yesNoB = pending { id ->
            (yesNoA.pendingDecision as YesNoDecision).copy(
                id = id,
                yesText = "Do it",
                noText = "Do not do it",
                hint = "A different UI hint",
            )
        }
        ReplayFingerprint.of(yesNoA, 3) shouldBe ReplayFingerprint.of(yesNoB, 3)

        val batchA = pending { id ->
            BatchYesNoDecision(
                id = id,
                playerId = EntityId("p1"),
                prompt = "same batch question",
                context = DecisionContext(),
                count = 2,
                yesText = "Accept all",
                noText = "Decline all",
            )
        }
        val batchB = pending { id ->
            (batchA.pendingDecision as BatchYesNoDecision).copy(
                id = id,
                yesText = "All yes",
                noText = "All no",
            )
        }
        ReplayFingerprint.of(batchA, 3) shouldBe ReplayFingerprint.of(batchB, 3)

        val modesA = pending { id ->
            ChooseModeDecision(
                id = id,
                playerId = EntityId("p1"),
                prompt = "same mode question",
                context = DecisionContext(),
                modes = listOf(ModeOption(index = 0, text = "Draw two cards", available = true)),
            )
        }
        val modesB = pending { id ->
            (modesA.pendingDecision as ChooseModeDecision).copy(
                id = id,
                modes = listOf(ModeOption(index = 0, text = "A completely different label", available = true)),
            )
        }
        ReplayFingerprint.of(modesA, 3) shouldBe ReplayFingerprint.of(modesB, 3)
    }

    test("v3 excludes audited decision display metadata but retains transition constraints") {
        val player = EntityId("p1")
        val first = EntityId("e1")
        val second = EntityId("e2")
        val contextA = DecisionContext(
            sourceId = EntityId("source"),
            sourceName = "Source A",
            triggeringEntityId = EntityId("trigger"),
            inlineOnTrigger = false,
            effectHint = "Effect A",
        )
        val contextB = contextA.copy(
            sourceName = "Source B",
            inlineOnTrigger = true,
            effectHint = "Effect B",
        )
        val cardInfoA = SearchCardInfo(
            name = "Card A",
            manaCost = "{1}",
            typeLine = "Creature",
            imageUri = "a.png",
            colors = listOf("G"),
            power = 1,
        )
        val cardInfoB = cardInfoA.copy(
            name = "Card B",
            manaCost = "{2}",
            typeLine = "Artifact",
            imageUri = "b.png",
            colors = listOf("R"),
            power = 2,
        )

        val states = listOf(
            pending { id ->
                ChooseTargetsDecision(
                    id = id,
                    playerId = player,
                    prompt = "same prompt",
                    context = contextA,
                    targetRequirements = listOf(
                        TargetRequirementInfo(
                            index = 0,
                            description = "Target A",
                            minTargets = 1,
                            maxTargets = 1,
                            targetZone = null,
                            mustDifferFromEarlier = false,
                            sameController = false,
                            sameOwner = true,
                            sameCreatureType = false,
                            sameCardType = false,
                            totalManaValueAtMost = 3,
                            differentNames = true,
                            xConstrainsManaValue = false,
                            xConstrainsManaValueExactly = false,
                            xConstrainsPower = false,
                            xConstrainsCount = false,
                        ),
                    ),
                    legalTargets = mapOf(0 to listOf(first)),
                    canCancel = true,
                )
            } to pending { id ->
                ChooseTargetsDecision(
                    id = id,
                    playerId = player,
                    prompt = "different prompt",
                    context = contextB,
                    targetRequirements = listOf(
                        TargetRequirementInfo(
                            index = 0,
                            description = "Target B",
                            minTargets = 1,
                            maxTargets = 1,
                            targetZone = null,
                            mustDifferFromEarlier = false,
                            sameController = false,
                            sameOwner = true,
                            sameCreatureType = false,
                            sameCardType = false,
                            totalManaValueAtMost = 3,
                            differentNames = true,
                            xConstrainsManaValue = false,
                            xConstrainsManaValueExactly = false,
                            xConstrainsPower = false,
                            xConstrainsCount = false,
                        ),
                    ),
                    legalTargets = mapOf(0 to listOf(first)),
                    canCancel = true,
                )
            },
            pending { id ->
                SelectCardsDecision(
                    id = id,
                    playerId = player,
                    prompt = "same prompt",
                    context = contextA,
                    options = listOf(first, second),
                    minSelections = 1,
                    maxSelections = 2,
                    ordered = true,
                    cardInfo = mapOf(first to cardInfoA),
                    useTargetingUI = false,
                    selectedLabel = "Selected A",
                    remainderLabel = "Remainder A",
                    nonSelectableOptions = listOf(second),
                    onePerCardType = true,
                    onePerColor = true,
                    availableColors = listOf("G"),
                    onePerCardName = true,
                    onePerBasicLandType = true,
                    onePerPower = true,
                    maxTotalManaValue = 4,
                    minTotalManaValue = 1,
                    maxTotalPower = 3,
                    conditionalMinimums = listOf(
                        ConditionalSelectionMinimum(
                            requiredSelections = 2,
                            minimumSelections = 1,
                            matchingOptions = listOf(first),
                            requiredMatches = 1,
                            description = "Condition A",
                        ),
                    ),
                )
            } to pending { id ->
                SelectCardsDecision(
                    id = id,
                    playerId = player,
                    prompt = "different prompt",
                    context = contextB,
                    options = listOf(first, second),
                    minSelections = 1,
                    maxSelections = 2,
                    ordered = true,
                    cardInfo = mapOf(first to cardInfoB),
                    useTargetingUI = true,
                    selectedLabel = "Selected B",
                    remainderLabel = "Remainder B",
                    nonSelectableOptions = emptyList(),
                    onePerCardType = true,
                    onePerColor = true,
                    availableColors = listOf("G"),
                    onePerCardName = true,
                    onePerBasicLandType = true,
                    onePerPower = true,
                    maxTotalManaValue = 4,
                    minTotalManaValue = 1,
                    maxTotalPower = 3,
                    conditionalMinimums = listOf(
                        ConditionalSelectionMinimum(
                            requiredSelections = 2,
                            minimumSelections = 1,
                            matchingOptions = listOf(first),
                            requiredMatches = 1,
                            description = "Condition B",
                        ),
                    ),
                )
            },
            pending { id ->
                SearchLibraryDecision(
                    id = id,
                    playerId = player,
                    prompt = "Search A",
                    context = contextA,
                    options = listOf(first),
                    minSelections = 0,
                    maxSelections = 1,
                    cards = mapOf(first to cardInfoA),
                    filterDescription = "A filter",
                )
            } to pending { id ->
                SearchLibraryDecision(
                    id = id,
                    playerId = player,
                    prompt = "Search B",
                    context = contextB,
                    options = listOf(first),
                    minSelections = 0,
                    maxSelections = 1,
                    cards = mapOf(first to cardInfoB),
                    filterDescription = "A different filter",
                )
            },
            pending { id ->
                ReorderLibraryDecision(
                    id = id,
                    playerId = player,
                    prompt = "Reorder A",
                    context = contextA,
                    cards = listOf(first, second),
                    cardInfo = mapOf(first to cardInfoA),
                )
            } to pending { id ->
                ReorderLibraryDecision(
                    id = id,
                    playerId = player,
                    prompt = "Reorder B",
                    context = contextB,
                    cards = listOf(first, second),
                    cardInfo = mapOf(first to cardInfoB),
                )
            },
            pending { id ->
                ChooseOptionDecision(
                    id = id,
                    playerId = player,
                    prompt = "Option A",
                    context = contextA,
                    options = listOf("one", "two"),
                    defaultSearch = "Search A",
                    optionCardIds = mapOf(0 to listOf(first)),
                    optionMetadata = listOf(OptionMetadata(id = "one", description = "A", iconKey = "a")),
                    canCancel = true,
                )
            } to pending { id ->
                ChooseOptionDecision(
                    id = id,
                    playerId = player,
                    prompt = "Option B",
                    context = contextB,
                    options = listOf("one", "two"),
                    defaultSearch = "Search B",
                    optionCardIds = mapOf(0 to listOf(second)),
                    optionMetadata = listOf(OptionMetadata(id = "one", description = "B", iconKey = "b")),
                    canCancel = true,
                )
            },
            pending { id ->
                ChooseReplacementDecision(
                    id = id,
                    playerId = player,
                    prompt = "Replacement A",
                    context = contextA,
                    fromOptions = listOf("Forest"),
                    toOptions = listOf("Island"),
                    fromMetadata = listOf(OptionMetadata(id = "forest", description = "A", iconKey = "a")),
                    toMetadata = listOf(OptionMetadata(id = "island", description = "A", iconKey = "a")),
                    allowedToByFrom = listOf(listOf(0)),
                    defaultFromIndex = 0,
                )
            } to pending { id ->
                ChooseReplacementDecision(
                    id = id,
                    playerId = player,
                    prompt = "Replacement B",
                    context = contextB,
                    fromOptions = listOf("Forest"),
                    toOptions = listOf("Island"),
                    fromMetadata = listOf(OptionMetadata(id = "forest", description = "B", iconKey = "b")),
                    toMetadata = listOf(OptionMetadata(id = "island", description = "B", iconKey = "b")),
                    allowedToByFrom = listOf(listOf(0)),
                    defaultFromIndex = 0,
                )
            },
            pending { id ->
                OrderObjectsDecision(
                    id = id,
                    playerId = player,
                    prompt = "Order A",
                    context = contextA,
                    objects = listOf(first, second),
                    cardInfo = mapOf(first to cardInfoA),
                    objectLabels = mapOf(first to "Display label A"),
                )
            } to pending { id ->
                OrderObjectsDecision(
                    id = id,
                    playerId = player,
                    prompt = "Order B",
                    context = contextB,
                    objects = listOf(first, second),
                    cardInfo = mapOf(first to cardInfoB),
                    objectLabels = mapOf(first to "Display label B"),
                )
            },
            pending { id ->
                SplitPilesDecision(
                    id = id,
                    playerId = player,
                    prompt = "Split A",
                    context = contextA,
                    cards = listOf(first, second),
                    numberOfPiles = 2,
                    pileLabels = listOf("Keep A", "Discard A"),
                    cardInfo = mapOf(first to cardInfoA),
                )
            } to pending { id ->
                SplitPilesDecision(
                    id = id,
                    playerId = player,
                    prompt = "Split B",
                    context = contextB,
                    cards = listOf(first, second),
                    numberOfPiles = 2,
                    pileLabels = listOf("Keep B", "Discard B"),
                    cardInfo = mapOf(first to cardInfoB),
                )
            },
            pending { id ->
                SelectManaSourcesDecision(
                    id = id,
                    playerId = player,
                    prompt = "Mana A",
                    context = contextA,
                    availableSources = listOf(
                        ManaSourceOption(
                            entityId = first,
                            name = "Forest A",
                            producesColors = emptySet(),
                            producesColorless = true,
                        ),
                    ),
                    requiredCost = "{1}",
                    autoPaySuggestion = listOf(first),
                    waterbendPermanents = listOf(WaterbendPermanentChoice(first, "Permanent A", false)),
                )
            } to pending { id ->
                SelectManaSourcesDecision(
                    id = id,
                    playerId = player,
                    prompt = "Mana B",
                    context = contextB,
                    availableSources = listOf(
                        ManaSourceOption(
                            entityId = first,
                            name = "Forest B",
                            producesColors = emptySet(),
                            producesColorless = true,
                        ),
                    ),
                    requiredCost = "{1}",
                    autoPaySuggestion = listOf(first),
                    waterbendPermanents = listOf(WaterbendPermanentChoice(first, "Permanent B", true)),
                )
            },
            pending { id ->
                BudgetModalDecision(
                    id = id,
                    playerId = player,
                    prompt = "Budget A",
                    context = contextA,
                    budget = 2,
                    modes = listOf(BudgetModeOption(cost = 1, description = "Mode A")),
                )
            } to pending { id ->
                BudgetModalDecision(
                    id = id,
                    playerId = player,
                    prompt = "Budget B",
                    context = contextB,
                    budget = 2,
                    modes = listOf(BudgetModeOption(cost = 1, description = "Mode B")),
                )
            },
        )

        states.forEach { (a, b) ->
            ReplayFingerprint.of(a, 3) shouldBe ReplayFingerprint.of(b, 3)
        }

        // Re-asked from the same routing allocator position as states[n].first, so the changed
        // field is the only difference.
        val semanticChange = pending { id ->
            (states[1].first.pendingDecision as SelectCardsDecision).copy(id = id, minSelections = 2)
        }
        ReplayFingerprint.of(semanticChange, 3) shouldNotBe
            ReplayFingerprint.of(states[1].first, 3)

        val semanticColorChange = pending { id ->
            (states[1].first.pendingDecision as SelectCardsDecision).copy(id = id, availableColors = listOf("R"))
        }
        ReplayFingerprint.of(semanticColorChange, 3) shouldNotBe
            ReplayFingerprint.of(states[1].first, 3)

        val targetZoneChange = (states[0].first.pendingDecision as ChooseTargetsDecision)
            .targetRequirements.single()
            .copy(targetZone = "Battlefield")
        val changedTargetDomain = pending { id ->
            (states[0].first.pendingDecision as ChooseTargetsDecision).copy(
                id = id,
                targetRequirements = listOf(targetZoneChange),
            )
        }
        ReplayFingerprint.of(changedTargetDomain, 3) shouldBe ReplayFingerprint.of(states[0].first, 3)
    }

    test("decision routing fields remain present through shared canonical aliases") {
        // The question and the answer continuation that consumes it are one Suspension frame on
        // the continuation stack; the answer no longer carries a decisionId of its own, the frame
        // itself is the routing relationship.
        val state = GameState().suspendForDecision(
            question = { id -> decision(id) },
            answer = LegendRuleContinuation(
                playerId = EntityId("p1"),
                allDuplicates = listOf(EntityId("e1")),
            ),
        ).state
        val routingId = state.pendingDecision!!.id

        val canonical = TransitionSemanticGameStateCanonicalizer.canonicalJson(state)

        canonical shouldContain "\"continuationStack\""
        canonical shouldContain "\"question\""
        canonical shouldContain "\"answer\""
        canonical shouldContain "\"allDuplicates\""
        canonical shouldContain "\"id\":\"D0\""
        canonical.contains("\"$routingId\"").shouldBeFalse()
    }

    test("canonical inventory covers every serialized GameState constructor field") {
        val canonical = persistenceJson.parseToJsonElement(
            TransitionSemanticGameStateCanonicalizer.canonicalJson(GameState())
        ).jsonObject
        val descriptor = GameState.serializer().descriptor

        (0 until descriptor.elementsCount).forEach { index ->
            canonical.containsKey(descriptor.getElementName(index)).shouldBeTrue()
        }
        canonical.containsKey("projectedState").shouldBeFalse()
    }
})
