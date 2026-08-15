# Named Counter + Marked-Permanent LKI Primitive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the missing central BOUNTY counter vocabulary and prove that the existing generic named-counter LKI path satisfies the reusable primitive required by the eventual Chevill card.

**Architecture:** Keep `EntitySnapshot.counters` as the sole frozen counter store. Reuse `GameObjectFilter.withCounter` and `StatePredicate.HasCounter` through the existing zone-change matcher. Add no Chevill-specific tracker, no new snapshot state, and no duplicate predicate family.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, Kotest, existing Argentum rules-engine test fixtures, TypeScript/Vite web client.

---

## Task 1: Record and lock the source-audit result

- [x] Review the current `ZoneTransitionService`, `EntitySnapshot`, `ZoneChangeEvent`, `TriggerContext`, `TriggerMatcher`, `DeathAndLeaveTriggerDetector`, and `GameObjectFilter` paths.
- [x] Confirm the classification is `FEATURE_ALREADY_EXISTS` and that the only missing central vocabulary is BOUNTY.
- [x] Keep the official CR snapshot and the no-duplicate-state decision in `docs/superpowers/specs/2026-08-15-named-counter-lki-design.md`.
- [x] Do not edit production Kotlin before the red tests exist.

## Task 2: Add red characterization and serialization tests

- [x] Add `rules-engine/src/test/kotlin/com/wingedsheep/engine/event/NamedCounterLkiTest.kt` with direct synthetic zone-change events for LKI-01 through LKI-07 and LKI-10.
- [x] Add a simultaneous source/marked-object scenario in the same engine-level test file for LKI-08, reusing the existing `GameTestDriver` and `TriggerDetector` conventions.
- [x] Add `mtg-sdk/src/test/kotlin/com/wingedsheep/sdk/serialization/NamedCounterLkiSerializationTest.kt` for LKI-09, including `GameObjectFilter` and `CounterType` round trips.
- [x] Use `Counters.BOUNTY` in the tests so the pre-vocabulary run fails at compile time with the expected missing-symbol error.
- [x] Run the focused tests and record the intentional RED result before implementing BOUNTY.

## Task 3: Add the central BOUNTY vocabulary

- [x] Add `BOUNTY` to `CounterType` and `Counters` in `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/core/CounterType.kt`.
- [x] Verify the existing `fromName`, counter-string conversion, and serializer paths need no special-case branch; add only a branch if a focused test proves one is required.
- [x] Add the matching client `CounterType` enum value and display label.
- [x] Add a generic existing mana-font icon class, a passive-counter list entry, and a dedicated palette row so the client wiring contract has no undefined-icon or fallback-color gap.
- [x] Do not add BOUNTY to `StateProjector.KEYWORD_COUNTER_MAP`; it is a passive marker counter with no inherent keyword behavior.

## Task 4: Make the authoring contract discoverable

- [x] Update `docs/card-sdk-language-reference.md` to list BOUNTY in the central passive named-counter workflow and state that named-counter LKI matching is provided by `withCounter` on battlefield-exit filters.
- [x] State that LKI is snapshot-only and fail-closed when no snapshot exists.
- [x] Do not document or expose a Chevill-specific helper.

## Task 5: Run focused and regression verification

- [x] Run the named-counter engine test and serialization test through the repository's documented test-class script when available; if the `just` executable is unavailable on Windows, use the script/Gradle fallback and report it exactly.
- [x] Run `:rules-engine:test`, `:mtg-sdk:test`, and `:mtg-sets:scenarioTest` through the repository-approved locked wrapper.
- [x] Run the web-client typecheck/test gate if the package scripts expose one.
- [x] Check `git diff --check` and inspect the final scope for forbidden modules.
- [x] Classify any failure as pre-existing, upstream-resolved, or a new regression; do not rebless unrelated snapshots.

## Task 6: Final upstream check, review, and handoff

- [x] Fetch upstream again immediately before handoff and compare BOUNTY/LKI-related paths for overlap.
- [x] Verify `origin` is the fork and the branch is `agent/a8-feature-named-counter-lki`.
- [x] Commit the tests/docs and vocabulary/client changes with focused commit messages and the repository Co-Authored-By convention.
- [x] Push only to `chrismaghuhn/argentum-engine`.
- [x] Open a draft PR against the fork's `main`, referencing issues #1, #2, and #4; do not merge and do not target upstream.
- [x] If all acceptance and regression gates are green, report `ARG_DECK_02_PASS` and `PROCEED_TO_CHEVILL_CARD_DEFINITION`; otherwise keep the draft open with `ARG_DECK_02_PARTIAL` or `ARG_DECK_02_FAIL`.
