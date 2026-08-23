package com.wingedsheep.engine.handlers.actions.decision

import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ChooseModeDecision
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DistributeDecision
import com.wingedsheep.engine.core.DistributionResponse
import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.DamageAssignmentResponse
import com.wingedsheep.engine.core.ManaSourceOption
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.ModesChosenResponse
import com.wingedsheep.engine.core.OrderObjectsDecision
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.PilesSplitResponse
import com.wingedsheep.engine.core.SearchCardInfo
import com.wingedsheep.engine.core.SearchLibraryDecision
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SplitPilesDecision
import com.wingedsheep.engine.core.TargetRequirementInfo
import com.wingedsheep.engine.core.TargetsResponse
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull

class DecisionCompletenessValidatorTest : FunSpec({
    val chooser = EntityId.of("chooser")
    val first = EntityId.of("first")
    val second = EntityId.of("second")
    val third = EntityId.of("third")
    val unknown = EntityId.of("unknown")
    val context = DecisionContext()

    test("card selection rejects duplicate entity IDs") {
        val decision = SelectCardsDecision(
            id = "cards",
            playerId = chooser,
            prompt = "Choose two",
            context = context,
            options = listOf(first, second),
            minSelections = 2,
            maxSelections = 2
        )

        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("cards", listOf(first, first))
        ).shouldNotBeNull()
    }

    test("restriction-bearing card selection fails closed without current state") {
        val decision = SelectCardsDecision(
            id = "restricted-cards",
            playerId = chooser,
            prompt = "Choose distinct names",
            context = context,
            options = listOf(first, second),
            minSelections = 1,
            maxSelections = 2,
            onePerCardName = true
        )

        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("restricted-cards", listOf(first, second))
        ).shouldNotBeNull()
    }

    test("target response must cover every required target slot") {
        val decision = ChooseTargetsDecision(
            id = "targets",
            playerId = chooser,
            prompt = "Choose both",
            context = context,
            targetRequirements = listOf(
                TargetRequirementInfo(
                    index = 0,
                    description = "first",
                    minTargets = 1,
                    maxTargets = 1,
                    targetZone = null,
                    mustDifferFromEarlier = false,
                    sameController = false,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = false,
                    totalManaValueAtMost = null,
                    differentNames = false,
                    xConstrainsManaValue = false,
                    xConstrainsManaValueExactly = false,
                    xConstrainsPower = false,
                    xConstrainsCount = false,
                ),
                TargetRequirementInfo(
                    index = 1,
                    description = "second",
                    minTargets = 1,
                    maxTargets = 1,
                    targetZone = null,
                    mustDifferFromEarlier = false,
                    sameController = false,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = false,
                    totalManaValueAtMost = null,
                    differentNames = false,
                    xConstrainsManaValue = false,
                    xConstrainsManaValueExactly = false,
                    xConstrainsPower = false,
                    xConstrainsCount = false,
                )
            ),
            legalTargets = mapOf(0 to listOf(first), 1 to listOf(second))
        )

        DecisionValidators.validate(
            decision,
            TargetsResponse("targets", mapOf(0 to listOf(first)))
        ).shouldNotBeNull()
    }

    test("target response rejects a target repeated across a must-differ slot") {
        val decision = ChooseTargetsDecision(
            id = "must-differ",
            playerId = chooser,
            prompt = "Choose different targets",
            context = context,
            targetRequirements = listOf(
                TargetRequirementInfo(
                    index = 0,
                    description = "first",
                    minTargets = 1,
                    maxTargets = 1,
                    targetZone = null,
                    mustDifferFromEarlier = false,
                    sameController = false,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = false,
                    totalManaValueAtMost = null,
                    differentNames = false,
                    xConstrainsManaValue = false,
                    xConstrainsManaValueExactly = false,
                    xConstrainsPower = false,
                    xConstrainsCount = false,
                ),
                TargetRequirementInfo(
                    index = 1,
                    description = "another",
                    minTargets = 1,
                    maxTargets = 1,
                    targetZone = null,
                    mustDifferFromEarlier = true,
                    sameController = false,
                    sameOwner = false,
                    sameCreatureType = false,
                    sameCardType = false,
                    totalManaValueAtMost = null,
                    differentNames = false,
                    xConstrainsManaValue = false,
                    xConstrainsManaValueExactly = false,
                    xConstrainsPower = false,
                    xConstrainsCount = false,
                ),
            ),
            legalTargets = mapOf(0 to listOf(first), 1 to listOf(first, second)),
        )

        DecisionValidators.validate(
            decision,
            TargetsResponse(decision.id, mapOf(0 to listOf(first), 1 to listOf(first))),
        ).shouldNotBeNull()
    }

    test("state-dependent target restrictions fail closed without current state") {
        val cases = listOf(
            TargetRequirementInfo(
                index = 0,
                description = "same graveyard",
                minTargets = 2,
                maxTargets = 2,
                targetZone = null,
                mustDifferFromEarlier = false,
                sameController = false,
                sameOwner = true,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = null,
                differentNames = false,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false
            ),
            TargetRequirementInfo(
                index = 0,
                description = "mana value cap",
                minTargets = 2,
                maxTargets = 2,
                targetZone = null,
                mustDifferFromEarlier = false,
                sameController = false,
                sameOwner = false,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = 1,
                differentNames = false,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false
            ),
            TargetRequirementInfo(
                index = 0,
                description = "different names",
                minTargets = 2,
                maxTargets = 2,
                targetZone = null,
                mustDifferFromEarlier = false,
                sameController = false,
                sameOwner = false,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = null,
                differentNames = true,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false
            )
        )

        cases.forEach { requirement ->
            val decision = ChooseTargetsDecision(
                id = "state-required-${requirement.description}",
                playerId = chooser,
                prompt = requirement.description,
                context = context,
                targetRequirements = listOf(requirement),
                legalTargets = mapOf(0 to listOf(first, second))
            )
            DecisionValidators.validate(
                decision,
                TargetsResponse(decision.id, mapOf(0 to listOf(first, second)))
            ).shouldNotBeNull()
        }
    }

    test("target relation validation fails closed when authoritative entity metadata is missing") {
        val relationCases = listOf(
            "same owner" to TargetRequirementInfo(
                index = 0,
                description = "same owner",
                minTargets = 2,
                maxTargets = 2,
                targetZone = null,
                mustDifferFromEarlier = false,
                sameController = false,
                sameOwner = true,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = null,
                differentNames = false,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false,
            ),
            "same controller" to TargetRequirementInfo(
                index = 0,
                description = "same controller",
                minTargets = 2,
                maxTargets = 2,
                targetZone = null,
                mustDifferFromEarlier = false,
                sameController = true,
                sameOwner = false,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = null,
                differentNames = false,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false,
            ),
            "total mana value" to TargetRequirementInfo(
                index = 0,
                description = "total mana value",
                minTargets = 2,
                maxTargets = 2,
                targetZone = null,
                mustDifferFromEarlier = false,
                sameController = false,
                sameOwner = false,
                sameCreatureType = false,
                sameCardType = false,
                totalManaValueAtMost = 5,
                differentNames = false,
                xConstrainsManaValue = false,
                xConstrainsManaValueExactly = false,
                xConstrainsPower = false,
                xConstrainsCount = false,
            ),
        )

        relationCases.forEach { (label, requirement) ->
            var state = GameState()
                .withEntity(first, ComponentContainer())
                .withEntity(second, ComponentContainer())
            if (requirement.sameController) {
                state = state
                    .addToZone(ZoneKey(chooser, Zone.BATTLEFIELD), first)
                    .addToZone(ZoneKey(chooser, Zone.BATTLEFIELD), second)
            }
            val decision = ChooseTargetsDecision(
                id = "missing-$label",
                playerId = chooser,
                prompt = label,
                context = context,
                targetRequirements = listOf(requirement),
                legalTargets = mapOf(0 to listOf(first, second)),
            )

            DecisionValidators.validate(
                decision,
                TargetsResponse(decision.id, mapOf(0 to listOf(first, second))),
                state,
            ).shouldNotBeNull()
        }
    }

    test("ordinary modal choice rejects repeated modes") {
        val decision = ChooseModeDecision(
            id = "modes",
            playerId = chooser,
            prompt = "Choose two modes",
            context = context,
            modes = listOf(
                com.wingedsheep.engine.core.ModeOption(0, "A"),
                com.wingedsheep.engine.core.ModeOption(1, "B")
            ),
            minModes = 2,
            maxModes = 2
        )

        DecisionValidators.validate(
            decision,
            ModesChosenResponse("modes", listOf(0, 0))
        ).shouldNotBeNull()
    }

    test("distribution rejects negative amounts") {
        val decision = DistributeDecision(
            id = "distribution",
            playerId = chooser,
            prompt = "Distribute",
            context = context,
            totalAmount = 1,
            targets = listOf(first),
            allowPartial = true
        )

        DecisionValidators.validate(
            decision,
            DistributionResponse("distribution", mapOf(first to -1))
        ).shouldNotBeNull()
    }

    test("distribution rejects an overflowing integer sum") {
        val decision = DistributeDecision(
            id = "overflowing-distribution",
            playerId = chooser,
            prompt = "Distribute",
            context = context,
            totalAmount = 5,
            targets = listOf(first, second, third),
        )

        DecisionValidators.validate(
            decision,
            DistributionResponse(
                "overflowing-distribution",
                mapOf(first to Int.MAX_VALUE, second to Int.MAX_VALUE, third to 7),
            ),
        ).shouldNotBeNull()
    }

    test("manual mana payment rejects sources outside the advertised domain") {
        val decision = SelectManaSourcesDecision(
            id = "mana",
            playerId = chooser,
            prompt = "Pay",
            context = context,
            availableSources = listOf(
                ManaSourceOption(first, "Forest", setOf(Color.GREEN), producesColorless = false)
            ),
            requiredCost = "{G}",
            autoPaySuggestion = listOf(first)
        )

        DecisionValidators.validate(
            decision,
            ManaSourcesSelectedResponse("mana", selectedSources = listOf(unknown))
        ).shouldNotBeNull()
    }

    test("object ordering rejects duplicates and extra entries") {
        val decision = OrderObjectsDecision(
            id = "order",
            playerId = chooser,
            prompt = "Order",
            context = context,
            objects = listOf(first, second)
        )

        DecisionValidators.validate(
            decision,
            OrderedResponse("order", listOf(first, first, second))
        ).shouldNotBeNull()
    }

    test("pile split rejects repeated cards across piles") {
        val decision = SplitPilesDecision(
            id = "piles",
            playerId = chooser,
            prompt = "Split",
            context = context,
            cards = listOf(first, second),
            numberOfPiles = 2
        )

        DecisionValidators.validate(
            decision,
            PilesSplitResponse("piles", listOf(listOf(first, first), listOf(second)))
        ).shouldNotBeNull()
    }

    test("library search enforces its advertised minimum and distinctness") {
        val decision = SearchLibraryDecision(
            id = "search",
            playerId = chooser,
            prompt = "Search",
            context = context,
            options = listOf(first, second),
            minSelections = 1,
            maxSelections = 2,
            cards = mapOf(
                first to SearchCardInfo("A", "", ""),
                second to SearchCardInfo("B", "", "")
            ),
            filterDescription = "a card"
        )

        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("search", emptyList())
        ).shouldNotBeNull()
        DecisionValidators.validate(
            decision,
            CardsSelectedResponse("search", listOf(first, first))
        ).shouldNotBeNull()
    }

    test("legacy damage assignment rejects negative amounts") {
        val decision = AssignDamageDecision(
            id = "damage",
            playerId = chooser,
            prompt = "Assign",
            context = context,
            attackerId = first,
            availablePower = 2,
            orderedTargets = listOf(second),
            defenderId = null,
            minimumAssignments = emptyMap(),
            defaultAssignments = emptyMap(),
            hasTrample = false,
            hasDeathtouch = false
        )

        DecisionValidators.validate(
            decision,
            DamageAssignmentResponse("damage", mapOf(second to -1))
        ).shouldNotBeNull()
    }
})
