package com.wingedsheep.engine.legalactions

import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.legalactions.support.setupP1
import com.wingedsheep.engine.legalactions.utils.TargetEnumerationUtils
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.sdk.scripting.targets.AnyTarget
import com.wingedsheep.sdk.scripting.targets.TargetObject
import com.wingedsheep.sdk.scripting.targets.TargetOther
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.targets.TargetChooser
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class TargetInfoProjectionTest : FunSpec({

    test("preserves relation-rich semantics and resolved zone metadata") {
        val driver = setupP1(
            graveyard = listOf("Grizzly Bears"),
        )
        val requirement = TargetObject(
            count = 2,
            minCount = 1,
            filter = TargetFilter.CardInGraveyard,
            sameController = true,
            sameOwner = true,
            sameCreatureType = true,
            sameCardType = true,
            totalManaValueAtMost = DynamicAmount.Fixed(5),
            differentNames = true,
        )

        val projection = TargetEnumerationUtils(PredicateEvaluator()).buildTargetInfos(
            state = driver.game.state,
            playerId = driver.player1,
            targetReqs = listOf(requirement),
        )

        projection.support shouldBe TargetDomainSupport.SUPPORTED
        projection.infos.single().let { info ->
            info.index shouldBe 0
            info.minTargets shouldBe 1
            info.maxTargets shouldBe 2
            info.targetZone shouldBe "Graveyard"
            info.sameController shouldBe true
            info.sameOwner shouldBe true
            info.sameCreatureType shouldBe true
            info.sameCardType shouldBe true
            info.totalManaValueAtMost shouldBe 5
            info.differentNames shouldBe true
            info.targetChooser shouldBe TargetChooser.Controller
        }
    }

    test("keeps semantic requirement order and preserves TargetOther distinctness") {
        val driver = setupP1(battlefield = listOf("Grizzly Bears", "Forest"))
        val first = TargetCreature(filter = TargetFilter.CreatureYouControl)
        val second = TargetOther(baseRequirement = TargetCreature(filter = TargetFilter.Creature))

        val projection = TargetEnumerationUtils(PredicateEvaluator()).buildTargetInfos(
            state = driver.game.state,
            playerId = driver.player1,
            targetReqs = listOf(first, second),
        )

        projection.support shouldBe TargetDomainSupport.SUPPORTED
        projection.infos.map { it.index } shouldBe listOf(0, 1)
        projection.infos[0].mustDifferFromEarlier shouldBe false
        projection.infos[1].mustDifferFromEarlier shouldBe true
    }

    test("unresolved cardinality and X are explicit unsupported results") {
        val driver = setupP1(battlefield = listOf("Grizzly Bears"))
        val unresolvedCount = TargetObject(
            filter = TargetFilter.Creature,
            dynamicMaxCount = DynamicAmount.XValue,
        )
        val countProjection = TargetEnumerationUtils(PredicateEvaluator()).buildTargetInfos(
            state = driver.game.state,
            playerId = driver.player1,
            targetReqs = listOf(unresolvedCount),
        )

        countProjection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.UNRESOLVED_X
        )
        countProjection.infos.single().xConstrainsCount shouldBe true

        val unresolvedAggregate = TargetObject(
            filter = TargetFilter.Creature,
            totalManaValueAtMost = DynamicAmount.XValue,
        )
        val aggregateProjection = TargetEnumerationUtils(PredicateEvaluator()).buildTargetInfos(
            state = driver.game.state,
            playerId = driver.player1,
            targetReqs = listOf(unresolvedAggregate),
        )

        aggregateProjection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.UNRESOLVED_X
        )
    }

    test("non-controller chooser fails closed without changing chooser ownership") {
        val driver = setupP1(battlefield = listOf("Grizzly Bears"))
        val projection = TargetEnumerationUtils(PredicateEvaluator()).buildTargetInfos(
            state = driver.game.state,
            playerId = driver.player1,
            targetReqs = listOf(AnyTarget(chooser = TargetChooser.Opponent)),
        )

        projection.support shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.NON_CONTROLLER_CHOOSER
        )
        projection.infos.single().targetChooser shouldBe TargetChooser.Opponent
    }

    test("direct LegalAction construction is canonical and fail-closed") {
        val targetless = LegalAction(
            action = PassPriority(EntityIdFixture.player),
            actionType = "PassPriority",
            description = "Pass priority",
        )
        targetless.targetRequirements shouldBe emptyList()
        targetless.targetDomainSupport shouldBe TargetDomainSupport.SUPPORTED

        val incompleteTargetAction = LegalAction(
            action = PassPriority(EntityIdFixture.player),
            actionType = "SyntheticTargetAction",
            description = "Synthetic target action",
            requiresTargets = true,
        )
        incompleteTargetAction.targetRequirements shouldBe emptyList()
        incompleteTargetAction.targetDomainSupport shouldBe TargetDomainSupport.UNSUPPORTED(
            TargetDomainUnsupportedReason.INCOMPLETE_SEMANTICS
        )
    }

    test("projection equality includes support metadata and does not collapse to a List") {
        val supported = TargetInfoProjection(emptyList(), TargetDomainSupport.SUPPORTED)
        val unsupported = TargetInfoProjection(
            emptyList(),
            TargetDomainSupport.UNSUPPORTED(TargetDomainUnsupportedReason.INCOMPLETE_SEMANTICS),
        )

        (supported == unsupported) shouldBe false
        (supported == emptyList<TargetInfo>()) shouldBe false
        setOf(supported, unsupported).size shouldBe 2
        listOf(supported, unsupported) shouldContain supported
    }
})

private object EntityIdFixture {
    val player = com.wingedsheep.sdk.model.EntityId("synthetic-player")
}
