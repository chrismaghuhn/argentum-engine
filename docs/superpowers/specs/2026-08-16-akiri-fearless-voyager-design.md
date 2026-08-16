# Akiri, Fearless Voyager — host-first resolution design

## Status

**APPROVED.** This is the user-approved design for `A8-CARD-001R2` in the
isolated branch `agent/a8-card-001r2-akiri`. The implementation is limited to
the ZNR card definition, its one-card scenario test, generated card snapshot
closure, and the Akiri-only curriculum classification.

## Authority

The current Oracle text and rulings are taken from the canonical ZNR printing:

- [Scryfall — Akiri, Fearless Voyager](https://scryfall.com/card/znr/220/akiri-fearless-voyager)
- [Wizards — Commander Masters release notes](https://magic.wizards.com/en/news/feature/commander-masters-release-notes)
- [Magic Comprehensive Rules](https://magic.wizards.com/en/rules)

The relevant Oracle clauses are:

```text
Whenever you attack a player with one or more equipped creatures, draw a card.

{W}: You may unattach an Equipment from a creature you control. If you do,
tap that creature and it gains indestructible until end of turn.
```

`{W}` is the activated ability's cost. The `may` is a resolution-time choice;
it is not permission to omit paying `{W}`. The Equipment is selected at
resolution, remains on the battlefield after being unattached, and the tap and
temporary indestructible happen as part of the same resolution with no priority
window between them.

## Design decision

Use a structured, host-first choice sequence built entirely from existing SDK
vocabulary:

```text
activate
  pay {W}
resolve
  may
    gather exactly the controller's creatures with an attached Equipment
    choose exactly one host creature (non-targeting, externally controlled)
    gather all Equipment attached to that stored host
    choose exactly one of those Equipment (non-targeting, externally controlled)
    unattach the stored Equipment
    if the unattach action succeeded:
      tap the stored host
      grant the stored host indestructible until end of turn
```

The intended card composition is:

```kotlin
effect = MayEffect(
    effect = Effects.IfYouDo(
        action = Effects.Pipeline {
            val hosts = gather(
                GameObjectFilter.Creature.youControl().equipped(),
                name = "hostCandidates",
            )
            val host = chooseExactly(
                count = 1,
                from = hosts,
                prompt = "Choose a creature you control with an attached Equipment",
                alwaysPrompt = true,
                name = "host",
            )
            val equipment = gather(
                CardSource.AttachedTo(
                    host = EffectTarget.PipelineTarget("host"),
                    filter = GameObjectFilter.Artifact.withSubtype(Subtype.EQUIPMENT),
                ),
                name = "equipmentCandidates",
            )
            val chosenEquipment = chooseExactly(
                count = 1,
                from = equipment,
                prompt = "Choose an Equipment to unattach",
                alwaysPrompt = true,
                name = "chosenEquipment",
            )
            run(
                ForEachInCollectionEffect(
                    collection = chosenEquipment.key,
                    effect = Effects.UnattachEquipment(EffectTarget.Self),
                )
            )
        },
        ifYouDo = Effects.Composite(
            Effects.Tap(EffectTarget.PipelineTarget("host")),
            Effects.GrantKeyword(
                Keyword.INDESTRUCTIBLE,
                EffectTarget.PipelineTarget("host"),
                Duration.EndOfTurn,
            ),
        ),
        successCriterion = SuccessCriterion.CollectionNonEmpty("chosenEquipment"),
    ),
    feasibility = FeasibilityCheck.ControlsPermanentMatching(
        GameObjectFilter.Creature.youControl().equipped(),
    ),
)
```

The exact imports and formatting may follow the repository's Kotlin style, but
the semantic shape above is binding. No raw engine constructor, new effect,
new trigger, or Akiri-specific executor is permitted.

## Legal-domain invariants

1. The first domain contains only creatures controlled by the ability's
   controller that currently have at least one attached Equipment.
2. The first choice is a non-targeting `SelectFromCollectionEffect`; it is not
   a target and does not emit a target event or trigger.
3. The second domain is computed from the stored host and contains every
   attached Equipment matching the Equipment subtype, regardless of the
   Equipment's controller or owner.
4. Both choices use `alwaysPrompt = true`. A singleton domain is still exposed
   as a decision, and no collection-order or `first()` choice is introduced.
5. The selected host is retained under the named pipeline slot `host`. The
   post-unattach effects resolve that slot directly; they never reconstruct a
   former host from live attachment state.
6. The stored Equipment is retained under `chosenEquipment`. The success
   criterion is based on that selected collection, so an already-tapped host
   still receives indestructible and success is not inferred from a tap event.
7. A host choice and an Equipment choice are continuation boundaries only for
   the decision protocol. The engine must not return normal priority between
   them or between unattach and the tap/indestructible follow-up.
8. Empty or stale-at-resolution domains fail closed without inventing an
   Equipment, controller, host, target, or automatic choice. Activation remains
   legal when `{W}` can be paid.
9. The normal serializable effect tree and continuation state preserve both
   named collection slots across fork, pause, serialization, and resume.

## Trigger semantics

The first ability uses the existing generic
`Triggers.YouAttackPlayerWithFilter(GameObjectFilter.Creature.youControl().equipped())`.
Its per-defending-player semantics are already a generic engine contract and
are not changed by this card. The card definition must therefore prove:

- one trigger for several equipped attackers against the same player;
- one trigger per distinct player directly attacked;
- no trigger for an unequipped attack, a planeswalker/battle-only attack, or a
  mixed declaration with no qualifying player attack;
- the trigger still resolves if Akiri or the Equipment leaves after trigger
  creation.

## Test contract

`AkiriFearlessVoyagerScenarioTest.kt` is the only card test file. It covers the
full `AKIRI-01` through `AKIRI-22` acceptance matrix from the closure request,
including:

- one-per-player trigger grouping and non-player defender exclusion;
- the `{W}` activation cost and resolution-time `may` decline;
- explicit host and Equipment decisions with no arbitrary selection;
- multiple Equipment where the selected one detaches and the other remains;
- a Player 1 creature carrying a Player 2-controlled Equipment;
- an already-tapped host, end-of-turn indestructible expiry, and attachment
  invariants;
- source-leaves-before-resolution, stale/no-legal-resolution domains, and
  continuation/serialization/registry coverage where the existing scenario
  harness exposes those boundaries.

If the current harness cannot express a requested battle fixture, the test
records the limitation explicitly and still covers the supported planeswalker
case. It must not add a generic fixture or engine shortcut solely for Akiri.

## Scope boundary

In scope:

- the canonical ZNR card definition and metadata;
- the one-card scenario test;
- the ZNR card snapshot entry generated by the repository gate;
- the Akiri-only status/classification update in
  `docs/ml/curriculum/akiri-chevill-closure-backlog.md`;
- this design and its implementation plan.

Out of scope:

- generic combat, trigger, Equipment, duration, continuation, serialization,
  server, client, AI, Gym, Commander, Chevill, decklist, or ML-policy changes;
- changing the generic `YouAttackPlayerWithFilter` implementation;
- new SDK vocabulary or an Akiri-specific engine hack;
- broad snapshot reblessing or unrelated backlog cleanup;
- merging, marking the PR ready, or enabling auto-merge.

