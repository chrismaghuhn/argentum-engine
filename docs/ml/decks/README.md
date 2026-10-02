# P1 Standard deck pools

Featured sample lists of the top MTGGoldfish Standard paper archetypes, fetched 2026-10-02
(`C:/Dev/data/argentum-p1/standard-decks/fetch_decks.py`, main decks only). Every card was checked
against the card registry export; Boros Dwarves was dropped because three of its cards are not
implemented.

- `standard-train.json` — 10 decks the P1 Standard model trains on.
- `standard-heldout.json` — 4 decks it never trains on (Jeskai Artifacts, Boros Tokens, Selesnya
  Landfall, Golgari Midrange): the generalization test for the card-text features
  (docs/ml/p1-card-text-features.md).
- `standard-all.json` — both together.

Use with `-Dphase1.decks=<file>` on the Phase 1 collector, DAgger, PPO and tournament tasks.
