"""Closed validators for the Kotlin-produced C1 model-facing projection."""

from __future__ import annotations

import re
from typing import Any


class ModelFacingContractError(ValueError):
    """Raised when a model-facing input is outside the frozen C1 view."""


_ALIAS = re.compile(r"^entity-[0-9]+$")
_ROLES = {"SELF", "OPPONENT", "ABSENT", "UNKNOWN"}
_STRUCTURED_VERSIONS = {
    "targets": 2,
    "card-selection": 1,
    "mode-selection": 1,
    "distribution": 1,
    "ordering": 1,
    "split-piles": 1,
    "search-library": 1,
    "reorder-library": 1,
    "combat-resolution": 1,
    "mana-sources": 3,
    "replacement": 1,
    "budget-modal": 1,
}
_FEATURE_KEYS = {
    "type", "version", "schemaIdentity", "kind", "index", "occurrence", "modeIndex", "composition", "minCount", "maxCount",
    "targetCount", "candidateCount", "minTargets", "maxTargets", "targetZone",
    "mustDifferFromEarlier", "sameController", "sameOwner", "sameCreatureType",
    "sameCardType", "totalManaValueAtMost", "differentNames", "xConstrainsManaValue",
    "xConstrainsManaValueExactly", "xConstrainsPower", "xConstrainsCount", "canCancel",
    "optionsAliases", "nonSelectableOptionsAliases", "matchingOptionsAliases",
    "minSelections", "maxSelections", "ordered", "cardInfo", "onePerCardType",
    "onePerColor", "availableColors", "onePerCardName", "onePerBasicLandType",
    "onePerPower", "maxTotalManaValue", "minTotalManaValue", "maxTotalPower",
    "conditionalMinimums", "requiredSelections", "minimumSelections", "requiredMatches",
    "modes", "available", "minModes", "maxModes", "totalAmount", "targetsAliases",
    "minPerTarget", "maxPerTarget", "allowPartial", "objectsAliases", "objectLabels",
    "cardsAliases", "numberOfPiles", "filterDescription", "firstStrike", "attackers",
    "blockers", "defenders", "edges", "power", "toughness", "hasTrample",
    "hasDeathtouch", "hasFirstStrike", "hasDoubleStrike", "dealsDamageThisStep", "bandAlias",
    "blockedByAliases", "markedDamage", "blockedAttackerAliases", "defenderRole",
    "lifeOrLoyaltyOrDefense", "direction", "amount", "maximum", "lethal", "isTrampleDrain",
    "coChooserRole", "paymentDomain", "canDecline", "requiredCost", "outerAtomicCostUnits",
    "initialPoolBuckets", "sourceActivationOptions", "reservedOuterLifePayment",
    "fixedSelfDamageBudget", "productionChoices", "atomicActivationManaCostUnits",
    "activationSupportKind", "deterministicNonManaCosts", "activationCostOrderOptions",
    "fixedSelfDamageAmount", "symbolIndex", "unitIndexWithinSymbol", "allowedColors",
    "key", "availableAmount", "poolColor", "sourceSubtypes", "color", "activationCostOrder",
    "fromOptions", "toOptions", "fromMetadata", "toMetadata", "allowedToByFrom",
    "defaultFromIndex", "budget", "candidateAliases", "sourceAlias", "sourceAliases",
    "targetAlias", "targetAliases", "targetsAliases", "targetEntityAliases", "attachedToAlias", "attachmentAliases", "entityAlias",
    "cardDefinitionId", "name", "zone", "ownerRole", "controllerRole", "types", "subtypes",
    "colors", "keywords", "manaCost", "manaValue", "oracleText", "tapped", "summoningSick",
    "faceDown", "damageMarked", "counters", "attached", "attachmentCount", "decisionKind",
    "shape", "candidates", "structuredType", "affordable", "hasXCost", "maxAffordableX",
    "validSacrificeTargetsAliases", "sacrificeCount", "sacrificeMinCount", "sacrificeMaxCount",
    "requiresDamageDistribution", "isManaAbility", "requiresStructuredAction", "requiredPayloadFields",
    "isDecisionOption", "actionSemantics", "targetDomain", "attackDeclarationDomain",
    "blockerDeclarationDomain", "targetPaymentDomain", "repeatCountDomain", "actorRole",
    "abilityKey", "xValue", "manaColorChoice", "castFaceDown", "declaredCostSlot",
    "wasWaterbendPaid", "modeSlots", "modeTargetSlots", "graveyardLifeCost", "useAlternativeCost",
    "useWithoutPayingManaCost", "alternativeCostType", "repeatCount", "optionMetadata",
    "triggeringPlayerRole", "choice", "selectedModes", "selectedCardsAliases", "number",
    "optionIndex", "selectedCards", "referenceType", "semantic", "orderedObjects", "piles", "distribution",
    "selectedTargets", "edgeId", "activations", "outerAllocation", "target", "resource",
    "activationIndex", "outputIndex", "productionChoice", "producedColor", "bonusChoice",
    "fixedOutputs", "activationCostAllocation", "objectLabels", "sourceColorBuckets",
    "attackerOrderAliases", "attackerToDefenders", "mandatoryAttackersAliases", "canDeclareZeroAttackers",
    "maxAttackers", "coAttackerRequirements", "anyOfAliases", "bandConstraints",
    "bandingAttackersByDefender", "nonBandingAttackersByDefender", "blockerOrderAliases",
    "blockerToAttackers", "maxAttackersByBlocker", "minBlockersByAttacker", "maxBlockersByAttacker",
    "globalMaxBlockers", "coBlockerRequirements", "eligibleCoBlockersAliases", "requirements",
    "minimumSatisfiedRequirementCount", "canDeclareZeroBlockers", "targetBindings", "targetAlias",
    "affordable", "blockerAlias", "attackerAlias", "attackerAliases", "attackerIdsAliases", "blockerIdsAliases",
    "sourceActivationOptions", "outerAtomicCostUnits", "activationCostOrderOptions",
}
_ALIAS_MAP_KEYS = {
    "cardInfo", "objectLabels", "maxPerTarget", "maxAttackersByBlocker", "minBlockersByAttacker",
    "maxBlockersByAttacker", "attackerToDefenders", "coAttackerRequirements", "bandingAttackersByDefender",
    "nonBandingAttackersByDefender", "blockerToAttackers", "coBlockerRequirements",
}
_ALIAS_LIST_MAP_KEYS = {
    "attackerToDefenders", "bandingAttackersByDefender", "nonBandingAttackersByDefender", "blockerToAttackers",
}
_RAW_OR_FORBIDDEN_KEYS = {
    "actionId", "decisionId", "policySeed", "outcome", "winnerId", "terminated", "truncated",
    "datasetId", "trajectoryId", "collectionJobId", "sourceManifestContentDigest", "provenance",
    "engineSeed", "hiddenWorld", "runtimeAbilityId", "pendingDecisionId", "sourceEntityId",
    "playerId", "cardId", "targetEntityIds", "entityId", "sourceId", "targetId", "manaAbilityKey",
}
_CANDIDATE_KEYS = {
    "kind", "affordable", "sourceAlias", "targetEntityAliases", "manaCost", "hasXCost", "maxAffordableX",
    "minTargets", "maxTargets", "validSacrificeTargetsAliases", "sacrificeCount", "sacrificeMinCount",
    "sacrificeMaxCount", "requiresDamageDistribution", "isManaAbility", "availableManaColors",
    "requiresStructuredAction", "requiredPayloadFields", "isDecisionOption", "actionSemantics",
    "targetDomain", "attackDeclarationDomain", "blockerDeclarationDomain", "paymentDomain",
    "targetPaymentDomain", "repeatCountDomain",
}
_CANDIDATE_REQUIRED_KEYS = {
    "kind", "affordable", "targetEntityAliases", "manaCost", "hasXCost", "maxAffordableX",
    "minTargets", "maxTargets", "validSacrificeTargetsAliases", "sacrificeCount", "sacrificeMinCount",
    "sacrificeMaxCount", "requiresDamageDistribution", "isManaAbility", "requiresStructuredAction",
    "requiredPayloadFields", "actionSemantics", "isDecisionOption",
}


def require_model_input(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ModelFacingContractError("model input must be an object")
    expected = {"decisionContext", "observation", "domain"}
    if set(value) != expected:
        raise ModelFacingContractError("model input has an unsupported wrapper shape")
    return value


def validate_model_input(
    value: Any,
    *,
    aliases: dict[str, str],
    source_domain: dict[str, Any],
) -> None:
    input_value = require_model_input(value)
    _validate_decision_context(input_value["decisionContext"], source_domain["kind"])
    _validate_observation(input_value["observation"], aliases)
    _validate_domain_view(input_value["domain"], aliases, source_domain)


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ModelFacingContractError(f"{label} must be an object")
    return value


def _list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ModelFacingContractError(f"{label} must be a list")
    return value


def _keys(value: dict[str, Any], allowed: set[str], label: str, required: set[str] | None = None) -> None:
    unknown = set(value) - allowed
    if unknown:
        raise ModelFacingContractError(f"{label} contains unknown fields: {sorted(unknown)}")
    if required and not required.issubset(value):
        raise ModelFacingContractError(f"{label} is missing fields: {sorted(required - set(value))}")


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise ModelFacingContractError(f"{label} must be a string")
    return value


def _integer(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ModelFacingContractError(f"{label} must be an integer")
    return value


def _boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        raise ModelFacingContractError(f"{label} must be a boolean")
    return value


def _alias(value: Any, aliases: dict[str, str], label: str) -> None:
    value = _string(value, label)
    if not _ALIAS.fullmatch(value) or value not in aliases:
        raise ModelFacingContractError(f"{label} is not a bound local entity alias")


def _alias_list(value: Any, aliases: dict[str, str], label: str) -> None:
    for index, child in enumerate(_list(value, label)):
        _alias(child, aliases, f"{label}[{index}]")


def _role(value: Any, label: str) -> None:
    if _string(value, label) not in {"SELF", "OPPONENT", "ABSENT", "UNKNOWN"}:
        raise ModelFacingContractError(f"{label} has an unsupported role")


def _validate_decision_context(value: Any, domain_kind: str) -> None:
    obj = _object(value, "decisionContext")
    allowed = {"domainKind", "turnNumber", "phase", "step", "agentToActRole", "activePlayerRole", "priorityPlayerRole"}
    _keys(obj, allowed, "decisionContext", allowed)
    if _string(obj["domainKind"], "decisionContext.domainKind") != domain_kind:
        raise ModelFacingContractError("decisionContext domain kind mismatch")
    _integer(obj["turnNumber"], "decisionContext.turnNumber")
    _string(obj["phase"], "decisionContext.phase")
    _string(obj["step"], "decisionContext.step")
    for key in ("agentToActRole", "activePlayerRole", "priorityPlayerRole"):
        _role(obj[key], f"decisionContext.{key}")


def _validate_observation(value: Any, aliases: dict[str, str]) -> None:
    obj = _object(value, "observation")
    allowed = {"turnNumber", "phase", "step", "players", "zones", "stack", "pendingDecision"}
    _keys(obj, allowed, "observation", {"turnNumber", "phase", "step", "players", "zones", "stack"})
    _integer(obj["turnNumber"], "observation.turnNumber")
    _string(obj["phase"], "observation.phase")
    _string(obj["step"], "observation.step")
    for index, player in enumerate(_list(obj["players"], "observation.players")):
        _validate_player(player, aliases, index)
    for index, zone in enumerate(_list(obj["zones"], "observation.zones")):
        _validate_zone(zone, aliases, index)
    for index, stack in enumerate(_list(obj["stack"], "observation.stack")):
        _validate_stack_item(stack, aliases, index)
    if "pendingDecision" in obj and obj["pendingDecision"] is not None:
        _validate_pending(obj["pendingDecision"], aliases)


def _validate_player(value: Any, aliases: dict[str, str], index: int) -> None:
    obj = _object(value, f"observation.players[{index}]")
    allowed = {"entityAlias", "role", "lifeTotal", "handSize", "librarySize", "graveyardSize", "exileSize", "manaPool", "isPerspective", "isActive", "hasPriority", "hasLost"}
    _keys(obj, allowed, f"observation.players[{index}]", allowed)
    _alias(obj["entityAlias"], aliases, f"observation.players[{index}].entityAlias")
    _role(obj["role"], f"observation.players[{index}].role")
    for key in ("lifeTotal", "handSize", "librarySize", "graveyardSize", "exileSize"):
        _integer(obj[key], f"observation.players[{index}].{key}")
    _validate_mana_pool(obj["manaPool"], f"observation.players[{index}].manaPool")
    for key in ("isPerspective", "isActive", "hasPriority", "hasLost"):
        _boolean(obj[key], f"observation.players[{index}].{key}")


def _validate_mana_pool(value: Any, label: str) -> None:
    obj = _object(value, label)
    keys = {"white", "blue", "black", "red", "green", "colorless"}
    _keys(obj, keys, label, keys)
    for key in keys:
        _integer(obj[key], f"{label}.{key}")


def _validate_zone(value: Any, aliases: dict[str, str], index: int) -> None:
    obj = _object(value, f"observation.zones[{index}]")
    allowed = {"ownerRole", "zoneType", "hidden", "size", "cards"}
    _keys(obj, allowed, f"observation.zones[{index}]", allowed)
    _role(obj["ownerRole"], f"observation.zones[{index}].ownerRole")
    _string(obj["zoneType"], f"observation.zones[{index}].zoneType")
    _boolean(obj["hidden"], f"observation.zones[{index}].hidden")
    _integer(obj["size"], f"observation.zones[{index}].size")
    for card_index, card in enumerate(_list(obj["cards"], f"observation.zones[{index}].cards")):
        _validate_card(card, aliases, f"observation.zones[{index}].cards[{card_index}]")


def _validate_card(value: Any, aliases: dict[str, str], label: str) -> None:
    obj = _object(value, label)
    allowed = {"entityAlias", "cardDefinitionId", "name", "zone", "ownerRole", "controllerRole", "types", "subtypes", "colors", "keywords", "manaCost", "manaValue", "oracleText", "power", "toughness", "tapped", "summoningSick", "faceDown", "damageMarked", "counters", "attached", "attachmentCount", "attachedToAlias", "attachmentAliases"}
    required = {"entityAlias", "zone", "ownerRole", "controllerRole", "types", "subtypes", "colors", "keywords", "manaCost", "manaValue", "oracleText", "tapped", "summoningSick", "faceDown", "damageMarked", "counters", "attached", "attachmentCount", "attachmentAliases"}
    _keys(obj, allowed, label, required)
    _alias(obj["entityAlias"], aliases, f"{label}.entityAlias")
    for key in ("cardDefinitionId", "name", "zone", "manaCost", "oracleText"):
        if key in obj:
            _string(obj[key], f"{label}.{key}")
    for key in ("ownerRole", "controllerRole"):
        _role(obj[key], f"{label}.{key}")
    for key in ("types", "subtypes", "colors", "keywords"):
        if any(not isinstance(item, str) for item in _list(obj[key], f"{label}.{key}")):
            raise ModelFacingContractError(f"{label}.{key} must contain strings")
    _integer(obj["manaValue"], f"{label}.manaValue")
    for key in ("power", "toughness"):
        if key in obj:
            _integer(obj[key], f"{label}.{key}")
    for key in ("tapped", "summoningSick", "faceDown", "attached"):
        _boolean(obj[key], f"{label}.{key}")
    _integer(obj["damageMarked"], f"{label}.damageMarked")
    counters = _object(obj["counters"], f"{label}.counters")
    for key, count in counters.items():
        _integer(count, f"{label}.counters.{key}")
    _integer(obj["attachmentCount"], f"{label}.attachmentCount")
    _alias_list(obj["attachmentAliases"], aliases, f"{label}.attachmentAliases")
    if "attachedToAlias" in obj:
        _alias(obj["attachedToAlias"], aliases, f"{label}.attachedToAlias")


def _validate_stack_item(value: Any, aliases: dict[str, str], index: int) -> None:
    obj = _object(value, f"observation.stack[{index}]")
    allowed = {"entityAlias", "name", "kind", "oracleText", "targetCount", "controllerRole", "sourceAlias", "targetAliases"}
    _keys(obj, allowed, f"observation.stack[{index}]", {"entityAlias", "name", "kind", "oracleText", "targetCount", "controllerRole", "targetAliases"})
    for key in ("entityAlias", "name", "kind", "oracleText"):
        _string(obj[key], f"observation.stack[{index}].{key}")
    _alias(obj["entityAlias"], aliases, f"observation.stack[{index}].entityAlias")
    _integer(obj["targetCount"], f"observation.stack[{index}].targetCount")
    _role(obj["controllerRole"], f"observation.stack[{index}].controllerRole")
    _alias_list(obj["targetAliases"], aliases, f"observation.stack[{index}].targetAliases")
    if "sourceAlias" in obj:
        _alias(obj["sourceAlias"], aliases, f"observation.stack[{index}].sourceAlias")


def _validate_pending(value: Any, aliases: dict[str, str]) -> None:
    obj = _object(value, "observation.pendingDecision")
    allowed = {"kind", "playerRole", "requiresStructuredResponse", "sourceAlias", "triggeringAlias", "minSelections", "maxSelections", "numericMin", "numericMax", "availableColors", "totalToDistribute", "budget"}
    _keys(obj, allowed, "observation.pendingDecision", {"kind", "playerRole", "requiresStructuredResponse", "minSelections", "maxSelections", "availableColors"})
    _string(obj["kind"], "observation.pendingDecision.kind")
    _role(obj["playerRole"], "observation.pendingDecision.playerRole")
    _boolean(obj["requiresStructuredResponse"], "observation.pendingDecision.requiresStructuredResponse")
    for key in ("minSelections", "maxSelections", "numericMin", "numericMax", "totalToDistribute", "budget"):
        if key in obj and obj[key] is not None:
            _integer(obj[key], f"observation.pendingDecision.{key}")
    if any(not isinstance(item, str) for item in _list(obj["availableColors"], "observation.pendingDecision.availableColors")):
        raise ModelFacingContractError("pending availableColors must contain strings")
    for key in ("sourceAlias", "triggeringAlias"):
        if key in obj:
            _alias(obj[key], aliases, f"observation.pendingDecision.{key}")


def _validate_domain_view(value: Any, aliases: dict[str, str], source_domain: dict[str, Any]) -> None:
    obj = _object(value, "domain")
    kind = source_domain["kind"]
    if _string(obj.get("kind"), "domain.kind") != kind:
        raise ModelFacingContractError("model domain kind mismatch")
    if kind == "STRUCTURED_DECISION":
        _keys(obj, {"kind", "decisionKind", "shape", "structuredType"}, "domain", {"kind", "decisionKind", "shape", "structuredType"})
        _string(obj["decisionKind"], "domain.decisionKind")
        _validate_shape(obj["shape"])
        expected_type = source_domain["structuredDomain"]["type"]
        structured = _object(obj["structuredType"], "domain.structuredType")
        if structured.get("type") != expected_type:
            raise ModelFacingContractError("model structured type mismatch")
        _validate_structured(structured, aliases)
        return
    expected = {"kind", "candidates"}
    if kind == "FOLDED_DECISION_OPTIONS":
        expected |= {"decisionKind", "shape"}
    _keys(obj, expected, "domain", expected)
    if kind == "FOLDED_DECISION_OPTIONS":
        _string(obj["decisionKind"], "domain.decisionKind")
        _validate_shape(obj["shape"])
    candidates = _list(obj["candidates"], "domain.candidates")
    if len(candidates) != len(source_domain["candidates"]):
        raise ModelFacingContractError("model candidate count does not match source domain")
    for index, candidate in enumerate(candidates):
        _validate_candidate(candidate, aliases, f"domain.candidates[{index}]")


def _validate_shape(value: Any) -> None:
    obj = _object(value, "domain.shape")
    allowed = {"minSelections", "maxSelections", "numericMin", "numericMax", "availableColors", "totalToDistribute", "budget"}
    _keys(obj, allowed, "domain.shape", allowed)
    for key in ("minSelections", "maxSelections"):
        _integer(obj[key], f"domain.shape.{key}")
    for key in ("numericMin", "numericMax", "totalToDistribute", "budget"):
        if obj[key] is not None:
            _integer(obj[key], f"domain.shape.{key}")
    if any(not isinstance(item, str) for item in _list(obj["availableColors"], "domain.shape.availableColors")):
        raise ModelFacingContractError("domain.shape.availableColors must contain strings")


def _validate_candidate(value: Any, aliases: dict[str, str], label: str) -> None:
    obj = _object(value, label)
    _keys(obj, _CANDIDATE_KEYS, label, _CANDIDATE_REQUIRED_KEYS)
    _string(obj["kind"], f"{label}.kind")
    _boolean(obj["affordable"], f"{label}.affordable")
    for key in ("sourceAlias", "targetAlias", "attachedToAlias"):
        if key in obj:
            _alias(obj[key], aliases, f"{label}.{key}")
    for key in ("targetEntityAliases", "validSacrificeTargetsAliases", "sourceAliases", "candidateAliases"):
        if key in obj:
            _alias_list(obj[key], aliases, f"{label}.{key}")
    if "actionSemantics" in obj:
        _validate_action_semantics(obj["actionSemantics"], aliases, f"{label}.actionSemantics")
    _validate_candidate_nested_domains(obj, aliases, label)
    for key, expected_version in {
        "targetDomain": 1,
        "repeatCountDomain": 1,
        "paymentDomain": 5,
        "targetPaymentDomain": 1,
        "attackDeclarationDomain": 2,
        "blockerDeclarationDomain": 1,
    }.items():
        if key in obj:
            nested = _object(obj[key], f"{label}.{key}")
            if nested.get("version") != expected_version:
                raise ModelFacingContractError(f"{label}.{key} has an unsupported version")
    for key, child in obj.items():
        if key in {"sourceAlias", "targetAlias", "attachedToAlias", "targetEntityAliases", "validSacrificeTargetsAliases", "sourceAliases", "candidateAliases", "actionSemantics"}:
            continue
        _validate_feature_tree(child, aliases, f"{label}.{key}")


def _validate_candidate_nested_domains(obj: dict[str, Any], aliases: dict[str, str], label: str) -> None:
    if "targetDomain" in obj:
        domain = _object(obj["targetDomain"], f"{label}.targetDomain")
        allowed = {"version", "composition", "requirements"}
        _keys(domain, allowed, f"{label}.targetDomain", allowed)
        if domain["version"] != 1 or domain["composition"] != "FIXED":
            raise ModelFacingContractError(f"{label}.targetDomain has an unsupported version")
        requirement_keys = {"index", "minTargets", "maxTargets", "candidateCount", "candidateAliases", "targetZone", "mustDifferFromEarlier", "sameController", "sameOwner", "sameCreatureType", "sameCardType", "totalManaValueAtMost", "differentNames", "xConstrainsManaValue", "xConstrainsManaValueExactly", "xConstrainsPower", "xConstrainsCount"}
        for index, requirement in enumerate(_list(domain["requirements"], f"{label}.targetDomain.requirements")):
            requirement_obj = _object(requirement, f"{label}.targetDomain.requirements[{index}]")
            _keys(requirement_obj, requirement_keys, f"{label}.targetDomain.requirements[{index}]")
            _alias_list(requirement_obj["candidateAliases"], aliases, f"{label}.targetDomain.requirements[{index}].candidateAliases")
    if "repeatCountDomain" in obj:
        repeat = _object(obj["repeatCountDomain"], f"{label}.repeatCountDomain")
        _keys(repeat, {"version", "minCount", "maxCount"}, f"{label}.repeatCountDomain", {"version", "minCount", "maxCount"})
        if repeat["version"] != 1 or repeat["minCount"] != 1:
            raise ModelFacingContractError(f"{label}.repeatCountDomain has an unsupported version")
    if "paymentDomain" in obj:
        _validate_projected_payment_domain(obj["paymentDomain"], aliases, f"{label}.paymentDomain")
    if "targetPaymentDomain" in obj:
        target_payment = _object(obj["targetPaymentDomain"], f"{label}.targetPaymentDomain")
        _keys(target_payment, {"version", "targetBindings"}, f"{label}.targetPaymentDomain", {"version", "targetBindings"})
        if target_payment["version"] != 1:
            raise ModelFacingContractError(f"{label}.targetPaymentDomain has an unsupported version")
        for index, binding in enumerate(_list(target_payment["targetBindings"], f"{label}.targetPaymentDomain.targetBindings")):
            binding_obj = _object(binding, f"{label}.targetPaymentDomain.targetBindings[{index}]")
            _keys(binding_obj, {"targetAlias", "affordable", "paymentDomain"}, f"{label}.targetPaymentDomain.targetBindings[{index}]", {"targetAlias", "affordable", "paymentDomain"})
            _alias(binding_obj["targetAlias"], aliases, f"{label}.targetPaymentDomain.targetBindings[{index}].targetAlias")
            _boolean(binding_obj["affordable"], f"{label}.targetPaymentDomain.targetBindings[{index}].affordable")
            _validate_projected_payment_domain(binding_obj["paymentDomain"], aliases, f"{label}.targetPaymentDomain.targetBindings[{index}].paymentDomain")
    for key, expected_version in (("attackDeclarationDomain", 2), ("blockerDeclarationDomain", 1)):
        if key in obj:
            nested = _object(obj[key], f"{label}.{key}")
            if nested.get("version") != expected_version:
                raise ModelFacingContractError(f"{label}.{key} has an unsupported version")
            if key == "attackDeclarationDomain":
                _keys(nested, {"version", "attackerOrderAliases", "attackerToDefenders", "mandatoryAttackersAliases", "canDeclareZeroAttackers", "maxAttackers", "coAttackerRequirements", "bandConstraints"}, f"{label}.{key}", {"version", "attackerOrderAliases", "attackerToDefenders", "mandatoryAttackersAliases", "canDeclareZeroAttackers", "maxAttackers", "coAttackerRequirements", "bandConstraints"})
            else:
                _keys(nested, {"version", "blockerOrderAliases", "attackerOrderAliases", "blockerToAttackers", "maxAttackersByBlocker", "minBlockersByAttacker", "maxBlockersByAttacker", "globalMaxBlockers", "coBlockerRequirements", "requirements", "minimumSatisfiedRequirementCount", "canDeclareZeroBlockers"}, f"{label}.{key}", {"version", "blockerOrderAliases", "attackerOrderAliases", "blockerToAttackers", "maxAttackersByBlocker", "minBlockersByAttacker", "maxBlockersByAttacker", "globalMaxBlockers", "coBlockerRequirements", "requirements", "minimumSatisfiedRequirementCount", "canDeclareZeroBlockers"})


def _validate_projected_payment_domain(value: Any, aliases: dict[str, str], label: str) -> None:
    domain = _object(value, label)
    allowed = {"version", "requiredCost", "outerAtomicCostUnits", "initialPoolBuckets", "sourceActivationOptions", "reservedOuterLifePayment", "fixedSelfDamageBudget"}
    _keys(domain, allowed, label, allowed)
    if domain["version"] != 5:
        raise ModelFacingContractError(f"{label} has an unsupported version")
    _validate_feature_tree(domain, aliases, label)


def _validate_action_semantics(value: Any, aliases: dict[str, str], label: str) -> None:
    obj = _object(value, label)
    response_types = {"YesNoResponse", "ModesChosenResponse", "ColorChosenResponse", "NumberChosenResponse", "OptionChosenResponse", "CardsSelectedResponse"}
    type_name = _string(obj.get("type"), f"{label}.type")
    if type_name in response_types:
        allowed = {
            "YesNoResponse": {"type", "choice"},
            "ModesChosenResponse": {"type", "selectedModes"},
            "ColorChosenResponse": {"type", "color"},
            "NumberChosenResponse": {"type", "number"},
            "OptionChosenResponse": {"type", "optionIndex", "optionMetadata"},
            "CardsSelectedResponse": {"type", "selectedCards"},
        }[type_name]
        _keys(obj, allowed, label, {"type"})
    else:
        allowed = {"type", "actorRole", "abilityKey", "cardAlias", "sourceAlias", "targetsAliases", "xValue", "manaColorChoice", "castFaceDown", "declaredCostSlot", "wasWaterbendPaid", "modeSlots", "modeTargetSlots", "graveyardLifeCost", "useAlternativeCost", "useWithoutPayingManaCost", "alternativeCostType", "color", "repeatCount"}
        _keys(obj, allowed, label, {"type"})
    if "actorRole" in obj:
        _role(obj["actorRole"], f"{label}.actorRole")
    for key in ("cardAlias", "sourceAlias"):
        if key in obj:
            _alias(obj[key], aliases, f"{label}.{key}")
    for key in ("targetsAliases",):
        if key in obj:
            _validate_typed_targets(obj[key], aliases, f"{label}.{key}")
    for key in ("selectedCards", "selectedCardsAliases"):
        if key in obj:
            _alias_list(obj[key], aliases, f"{label}.{key}")
    if "abilityKey" in obj:
        ability = _object(obj["abilityKey"], f"{label}.abilityKey")
        _keys(ability, {"origin", "cardDefinitionId", "ordinalRelation"}, f"{label}.abilityKey", {"origin"})
        _string(ability["origin"], f"{label}.abilityKey.origin")
        if "cardDefinitionId" in ability:
            _string(ability["cardDefinitionId"], f"{label}.abilityKey.cardDefinitionId")
        if "ordinalRelation" in ability:
            _string(ability["ordinalRelation"], f"{label}.abilityKey.ordinalRelation")
    if "modeSlots" in obj:
        for index, slot in enumerate(_list(obj["modeSlots"], f"{label}.modeSlots")):
            slot_obj = _object(slot, f"{label}.modeSlots[{index}]")
            _keys(slot_obj, {"occurrence", "modeIndex"}, f"{label}.modeSlots[{index}]", {"occurrence", "modeIndex"})
            _integer(slot_obj["occurrence"], f"{label}.modeSlots[{index}].occurrence")
            _integer(slot_obj["modeIndex"], f"{label}.modeSlots[{index}].modeIndex")
    if "modeTargetSlots" in obj:
        for index, slot in enumerate(_list(obj["modeTargetSlots"], f"{label}.modeTargetSlots")):
            slot_obj = _object(slot, f"{label}.modeTargetSlots[{index}]")
            _keys(slot_obj, {"occurrence", "targetCount", "targets"}, f"{label}.modeTargetSlots[{index}]", {"occurrence"})
            _integer(slot_obj["occurrence"], f"{label}.modeTargetSlots[{index}].occurrence")
            if "targetCount" in slot_obj:
                _integer(slot_obj["targetCount"], f"{label}.modeTargetSlots[{index}].targetCount")
            if "targets" in slot_obj:
                _validate_typed_targets(slot_obj["targets"], aliases, f"{label}.modeTargetSlots[{index}].targets")
            if ("targetCount" in slot_obj) == ("targets" in slot_obj):
                raise ModelFacingContractError(f"{label}.modeTargetSlots has invalid mode")
    for key, child in obj.items():
        if key in {"type", "actorRole", "cardAlias", "sourceAlias", "targetsAliases", "selectedCards", "selectedCardsAliases", "abilityKey", "modeSlots", "modeTargetSlots"}:
            continue
        if key == "optionMetadata":
            metadata = _object(child, f"{label}.optionMetadata")
            _keys(metadata, {"triggeringPlayerRole"}, f"{label}.optionMetadata")
            if "triggeringPlayerRole" in metadata:
                _role(metadata["triggeringPlayerRole"], f"{label}.optionMetadata.triggeringPlayerRole")
        else:
            _validate_feature_tree(child, aliases, f"{label}.{key}")


def _validate_typed_targets(value: Any, aliases: dict[str, str], label: str) -> None:
    for index, target in enumerate(_list(value, label)):
        if isinstance(target, str):
            _alias(target, aliases, f"{label}[{index}]")
            continue
        obj = _object(target, f"{label}[{index}]")
        target_type = _string(obj.get("type"), f"{label}[{index}].type")
        fields = {
            "Player": {"type", "playerAlias"},
            "Permanent": {"type", "entityAlias"},
            "Card": {"type", "cardAlias", "ownerAlias", "zone"},
            "Spell": {"type", "spellEntityAlias"},
        }.get(target_type)
        if fields is None:
            raise ModelFacingContractError(f"{label}[{index}] has an unsupported target type")
        _keys(obj, fields, f"{label}[{index}]", fields)
        for key in fields - {"type", "zone"}:
            _alias(obj[key], aliases, f"{label}[{index}].{key}")
        if "zone" in obj:
            _string(obj["zone"], f"{label}[{index}].zone")


def _validate_feature_tree(value: Any, aliases: dict[str, str], label: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in _RAW_OR_FORBIDDEN_KEYS or key not in _FEATURE_KEYS:
                raise ModelFacingContractError(f"{label} contains unknown or forbidden field: {key}")
            if key.endswith("Alias") and child is not None:
                _alias(child, aliases, f"{label}.{key}")
            elif key.endswith("Aliases") and child is not None:
                _alias_list(child, aliases, f"{label}.{key}")
            elif key in _ALIAS_MAP_KEYS and child is not None:
                mapping = _object(child, f"{label}.{key}")
                for map_key, map_value in mapping.items():
                    _alias(map_key, aliases, f"{label}.{key} key")
                    if key in _ALIAS_LIST_MAP_KEYS:
                        _alias_list(map_value, aliases, f"{label}.{key}[{map_key}]")
                    else:
                        _validate_feature_tree(map_value, aliases, f"{label}.{key}[{map_key}]")
            else:
                _validate_feature_tree(child, aliases, f"{label}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _validate_feature_tree(child, aliases, f"{label}[{index}]")


def _validate_structured(value: dict[str, Any], aliases: dict[str, str]) -> None:
    structured_type = _string(value.get("type"), "domain.structuredType.type")
    if structured_type not in _STRUCTURED_VERSIONS:
        raise ModelFacingContractError("unsupported structured type")
    if value.get("version") != _STRUCTURED_VERSIONS[structured_type]:
        raise ModelFacingContractError("unsupported structured domain version")
    validators = {
        "targets": _validate_targets,
        "card-selection": _validate_card_selection,
        "mode-selection": _validate_modes,
        "distribution": _validate_distribution,
        "ordering": _validate_ordering,
        "split-piles": _validate_split_piles,
        "search-library": _validate_search,
        "reorder-library": _validate_reorder,
        "combat-resolution": _validate_combat,
        "mana-sources": _validate_mana_sources,
        "replacement": _validate_replacement,
        "budget-modal": _validate_budget,
    }
    validators[structured_type](value, aliases)


def _validate_structured_keys(value: dict[str, Any], allowed: set[str], required: set[str], label: str) -> None:
    _keys(value, allowed, label, required)


def _validate_targets(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "requirements", "canCancel"}
    _validate_structured_keys(value, allowed, allowed, "targets")
    _boolean(value["canCancel"], "targets.canCancel")
    req_allowed = {"index", "minTargets", "maxTargets", "candidateAliases", "targetZone", "mustDifferFromEarlier", "sameController", "sameOwner", "sameCreatureType", "sameCardType", "totalManaValueAtMost", "differentNames", "xConstrainsManaValue", "xConstrainsManaValueExactly", "xConstrainsPower", "xConstrainsCount"}
    for index, requirement in enumerate(_list(value["requirements"], "targets.requirements")):
        obj = _object(requirement, f"targets.requirements[{index}]")
        _keys(obj, req_allowed, f"targets.requirements[{index}]", req_allowed)
        for key in ("index", "minTargets", "maxTargets"):
            _integer(obj[key], f"targets.requirements[{index}].{key}")
        _alias_list(obj["candidateAliases"], aliases, f"targets.requirements[{index}].candidateAliases")
        if obj["targetZone"] is not None:
            _string(obj["targetZone"], f"targets.requirements[{index}].targetZone")
        if obj["totalManaValueAtMost"] is not None:
            _integer(obj["totalManaValueAtMost"], f"targets.requirements[{index}].totalManaValueAtMost")
        for key in req_allowed - {"index", "minTargets", "maxTargets", "candidateAliases", "targetZone", "totalManaValueAtMost"}:
            _boolean(obj[key], f"targets.requirements[{index}].{key}")


def _validate_card_info(value: Any, aliases: dict[str, str], label: str) -> None:
    mapping = _object(value, label)
    for alias, info in mapping.items():
        _alias(alias, aliases, f"{label} key")
        obj = _object(info, f"{label}[{alias}]")
        allowed = {"name", "manaCost", "typeLine", "colors", "power"}
        _keys(obj, allowed, f"{label}[{alias}]", allowed)
        for key in ("name", "manaCost", "typeLine"):
            _string(obj[key], f"{label}[{alias}].{key}")
        if any(not isinstance(item, str) for item in _list(obj["colors"], f"{label}[{alias}].colors")):
            raise ModelFacingContractError(f"{label}[{alias}].colors must contain strings")
        if obj["power"] is not None:
            _integer(obj["power"], f"{label}[{alias}].power")


def _validate_card_selection(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "optionsAliases", "minSelections", "maxSelections", "ordered", "cardInfo", "nonSelectableOptionsAliases", "onePerCardType", "onePerColor", "availableColors", "onePerCardName", "onePerBasicLandType", "onePerPower", "maxTotalManaValue", "minTotalManaValue", "maxTotalPower", "conditionalMinimums"}
    _validate_structured_keys(value, allowed, allowed, "card-selection")
    _alias_list(value["optionsAliases"], aliases, "card-selection.optionsAliases")
    _alias_list(value["nonSelectableOptionsAliases"], aliases, "card-selection.nonSelectableOptionsAliases")
    for key in ("minSelections", "maxSelections"):
        _integer(value[key], f"card-selection.{key}")
    _boolean(value["ordered"], "card-selection.ordered")
    if value["cardInfo"] is not None:
        _validate_card_info(value["cardInfo"], aliases, "card-selection.cardInfo")
    for key in ("onePerCardType", "onePerColor", "onePerCardName", "onePerBasicLandType", "onePerPower"):
        _boolean(value[key], f"card-selection.{key}")
    if value["availableColors"] is not None and any(not isinstance(item, str) for item in _list(value["availableColors"], "card-selection.availableColors")):
        raise ModelFacingContractError("card-selection.availableColors must contain strings")
    for key in ("maxTotalManaValue", "minTotalManaValue", "maxTotalPower"):
        if value[key] is not None:
            _integer(value[key], f"card-selection.{key}")
    for index, minimum in enumerate(_list(value["conditionalMinimums"], "card-selection.conditionalMinimums")):
        obj = _object(minimum, f"card-selection.conditionalMinimums[{index}]")
        allowed_minimum = {"requiredSelections", "minimumSelections", "matchingOptionsAliases", "requiredMatches"}
        _keys(obj, allowed_minimum, f"card-selection.conditionalMinimums[{index}]", allowed_minimum)
        for key in ("requiredSelections", "minimumSelections", "requiredMatches"):
            _integer(obj[key], f"card-selection.conditionalMinimums[{index}].{key}")
        _alias_list(obj["matchingOptionsAliases"], aliases, f"card-selection.conditionalMinimums[{index}].matchingOptionsAliases")


def _validate_modes(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "modes", "minModes", "maxModes"}
    _validate_structured_keys(value, allowed, allowed, "mode-selection")
    for key in ("minModes", "maxModes"):
        _integer(value[key], f"mode-selection.{key}")
    for index, mode in enumerate(_list(value["modes"], "mode-selection.modes")):
        obj = _object(mode, f"mode-selection.modes[{index}]")
        _keys(obj, {"index", "available"}, f"mode-selection.modes[{index}]", {"index", "available"})
        _integer(obj["index"], f"mode-selection.modes[{index}].index")
        _boolean(obj["available"], f"mode-selection.modes[{index}].available")


def _validate_distribution(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "totalAmount", "targetsAliases", "minPerTarget", "maxPerTarget", "allowPartial"}
    _validate_structured_keys(value, allowed, allowed, "distribution")
    for key in ("totalAmount", "minPerTarget"):
        _integer(value[key], f"distribution.{key}")
    _alias_list(value["targetsAliases"], aliases, "distribution.targetsAliases")
    _validate_alias_int_map(value["maxPerTarget"], aliases, "distribution.maxPerTarget")
    _boolean(value["allowPartial"], "distribution.allowPartial")


def _validate_alias_int_map(value: Any, aliases: dict[str, str], label: str) -> None:
    mapping = _object(value, label)
    for key, child in mapping.items():
        _alias(key, aliases, f"{label} key")
        _integer(child, f"{label}[{key}]")


def _validate_ordering(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "objectsAliases", "cardInfo", "objectLabels"}
    _validate_structured_keys(value, allowed, {"type", "version", "objectsAliases", "cardInfo"}, "ordering")
    _alias_list(value["objectsAliases"], aliases, "ordering.objectsAliases")
    if value["cardInfo"] is not None:
        _validate_card_info(value["cardInfo"], aliases, "ordering.cardInfo")
    if value.get("objectLabels") is not None:
        labels = _object(value["objectLabels"], "ordering.objectLabels")
        for key, label in labels.items():
            _alias(key, aliases, "ordering.objectLabels key")
            _string(label, f"ordering.objectLabels[{key}]")


def _validate_split_piles(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "cardsAliases", "numberOfPiles", "cardInfo"}
    _validate_structured_keys(value, allowed, allowed, "split-piles")
    _alias_list(value["cardsAliases"], aliases, "split-piles.cardsAliases")
    _integer(value["numberOfPiles"], "split-piles.numberOfPiles")
    if value["cardInfo"] is not None:
        _validate_card_info(value["cardInfo"], aliases, "split-piles.cardInfo")


def _validate_search(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "optionsAliases", "minSelections", "maxSelections", "cards"}
    _validate_structured_keys(value, allowed, allowed, "search-library")
    _alias_list(value["optionsAliases"], aliases, "search-library.optionsAliases")
    for key in ("minSelections", "maxSelections"):
        _integer(value[key], f"search-library.{key}")
    _validate_card_info(value["cards"], aliases, "search-library.cards")


def _validate_reorder(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "cardsAliases", "cardInfo"}
    _validate_structured_keys(value, allowed, allowed, "reorder-library")
    _alias_list(value["cardsAliases"], aliases, "reorder-library.cardsAliases")
    _validate_card_info(value["cardInfo"], aliases, "reorder-library.cardInfo")


def _validate_combat(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "firstStrike", "attackers", "blockers", "defenders", "edges", "coChooserRole"}
    _validate_structured_keys(value, allowed, {"type", "version", "firstStrike", "attackers", "blockers", "defenders", "edges"}, "combat-resolution")
    _boolean(value["firstStrike"], "combat-resolution.firstStrike")
    if "coChooserRole" in value:
        _role(value["coChooserRole"], "combat-resolution.coChooserRole")
    for index, attacker in enumerate(_list(value["attackers"], "combat-resolution.attackers")):
        obj = _object(attacker, f"combat-resolution.attackers[{index}]")
        required = {"power", "toughness", "hasTrample", "hasDeathtouch", "hasFirstStrike", "hasDoubleStrike", "dealsDamageThisStep", "blockedByAliases", "markedDamage", "attackerAlias"}
        _keys(obj, required | {"bandAlias", "attackedDefenderRole", "attackedDefenderAlias"}, f"combat-resolution.attackers[{index}]", required)
        _validate_combat_unit(obj, aliases, f"combat-resolution.attackers[{index}]")
        if "attackedDefenderRole" in obj:
            _role(obj["attackedDefenderRole"], f"combat-resolution.attackers[{index}].attackedDefenderRole")
        if "attackedDefenderAlias" in obj:
            _alias(obj["attackedDefenderAlias"], aliases, f"combat-resolution.attackers[{index}].attackedDefenderAlias")
    for index, blocker in enumerate(_list(value["blockers"], "combat-resolution.blockers")):
        obj = _object(blocker, f"combat-resolution.blockers[{index}]")
        required = {"power", "toughness", "hasDeathtouch", "hasFirstStrike", "hasDoubleStrike", "dealsDamageThisStep", "blockedAttackerAliases", "markedDamage", "blockerAlias"}
        _keys(obj, required | {"bandAlias"}, f"combat-resolution.blockers[{index}]", required)
        _validate_combat_unit(obj, aliases, f"combat-resolution.blockers[{index}]")
    for index, defender in enumerate(_list(value["defenders"], "combat-resolution.defenders")):
        obj = _object(defender, f"combat-resolution.defenders[{index}]")
        _keys(obj, {"kind", "lifeOrLoyaltyOrDefense", "defenderRole", "defenderAlias"}, f"combat-resolution.defenders[{index}]", {"kind"})
        _string(obj["kind"], f"combat-resolution.defenders[{index}].kind")
        if obj.get("lifeOrLoyaltyOrDefense") is not None:
            _integer(obj["lifeOrLoyaltyOrDefense"], f"combat-resolution.defenders[{index}].lifeOrLoyaltyOrDefense")
        if "defenderRole" in obj:
            _role(obj["defenderRole"], f"combat-resolution.defenders[{index}].defenderRole")
        if "defenderAlias" in obj:
            _alias(obj["defenderAlias"], aliases, f"combat-resolution.defenders[{index}].defenderAlias")
    for index, edge in enumerate(_list(value["edges"], "combat-resolution.edges")):
        obj = _object(edge, f"combat-resolution.edges[{index}]")
        required = {"sourceAlias", "targetAlias", "direction", "amount", "maximum", "lethal", "isTrampleDrain", "editableByRole"}
        _keys(obj, required, f"combat-resolution.edges[{index}]", required)
        _alias(obj["sourceAlias"], aliases, f"combat-resolution.edges[{index}].sourceAlias")
        _alias(obj["targetAlias"], aliases, f"combat-resolution.edges[{index}].targetAlias")
        _string(obj["direction"], f"combat-resolution.edges[{index}].direction")
        for key in ("amount", "maximum", "lethal"):
            _integer(obj[key], f"combat-resolution.edges[{index}].{key}")
        _boolean(obj["isTrampleDrain"], f"combat-resolution.edges[{index}].isTrampleDrain")
        _role(obj["editableByRole"], f"combat-resolution.edges[{index}].editableByRole")


def _validate_combat_unit(obj: dict[str, Any], aliases: dict[str, str], label: str) -> None:
    for key in ("power", "toughness", "markedDamage"):
        _integer(obj[key], f"{label}.{key}")
    for key in ("hasTrample", "hasDeathtouch", "hasFirstStrike", "hasDoubleStrike", "dealsDamageThisStep"):
        if key in obj:
            _boolean(obj[key], f"{label}.{key}")
    for key in ("bandAlias", "attackerAlias", "blockerAlias"):
        if key in obj:
            _alias(obj[key], aliases, f"{label}.{key}")
    for key in ("blockedByAliases", "blockedAttackerAliases"):
        if key in obj:
            _alias_list(obj[key], aliases, f"{label}.{key}")


def _validate_mana_sources(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "paymentDomain", "canDecline"}
    _validate_structured_keys(value, allowed, allowed, "mana-sources")
    _boolean(value["canDecline"], "mana-sources.canDecline")
    payment = _object(value["paymentDomain"], "mana-sources.paymentDomain")
    if payment.get("version") != 5:
        raise ModelFacingContractError("mana-sources.paymentDomain has an unsupported version")
    _validate_feature_tree(payment, aliases, "mana-sources.paymentDomain")


def _validate_replacement(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "fromOptions", "toOptions", "fromMetadata", "toMetadata", "allowedToByFrom", "defaultFromIndex"}
    _validate_structured_keys(value, allowed, allowed, "replacement")
    for key in ("fromOptions", "toOptions"):
        if any(not isinstance(item, str) for item in _list(value[key], f"replacement.{key}")):
            raise ModelFacingContractError(f"replacement.{key} must contain strings")
    for key in ("fromMetadata", "toMetadata"):
        for index, metadata in enumerate(_list(value[key], f"replacement.{key}")):
            obj = _object(metadata, f"replacement.{key}[{index}]")
            _keys(obj, {"triggeringPlayerAlias"}, f"replacement.{key}[{index}]")
            if obj.get("triggeringPlayerAlias") is not None:
                _alias(obj["triggeringPlayerAlias"], aliases, f"replacement.{key}[{index}].triggeringPlayerAlias")
    for row in _list(value["allowedToByFrom"], "replacement.allowedToByFrom"):
        if any(not isinstance(item, int) or isinstance(item, bool) for item in _list(row, "replacement relation")):
            raise ModelFacingContractError("replacement relation must contain integers")
    if value["defaultFromIndex"] is not None:
        _integer(value["defaultFromIndex"], "replacement.defaultFromIndex")


def _validate_budget(value: dict[str, Any], aliases: dict[str, str]) -> None:
    allowed = {"type", "version", "budget", "modes"}
    _validate_structured_keys(value, allowed, allowed, "budget-modal")
    _integer(value["budget"], "budget-modal.budget")
    for index, mode in enumerate(_list(value["modes"], "budget-modal.modes")):
        obj = _object(mode, f"budget-modal.modes[{index}]")
        _keys(obj, {"cost"}, f"budget-modal.modes[{index}]", {"cost"})
        _integer(obj["cost"], f"budget-modal.modes[{index}].cost")
