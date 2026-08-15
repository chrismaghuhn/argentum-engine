# ARG-DECK-01 — four-variant legality audit

This persisted audit makes the current card-data checks reproducible for all four 100-slot variants, not only the recommended Friendly coverage matrix.

| field | value |
|---|---|
| Scryfall snapshot | current API named/collection data retrieved 2026-08-15 |
| official policy snapshot | live Commander/1v1 pages and official banned list retrieved 2026-08-15; B&R announcement 2026-02-09 |
| variants | Akiri Ideal, Chevill Ideal, Akiri Friendly, Chevill Friendly |
| rows | 400, exactly 100 per variant |
| commander legality | 400/400 `legal` |
| duel legality cross-check | 400/400 `legal` |
| official banned-list cross-check | 400/400 not listed in current Commander snapshot |
| Game Changers | 0/400 |
| color identity | 400/400 subset of commander identity |
| nonbasic singleton | 400/400; basic-land repeats allowed |

The CSV preserves the card name, Oracle ID, legality fields, color identities, Game Changer flag, and per-variant singleton/color-identity checks. `official_banned_check` records the manual cross-check against the official current Commander list; the official source remains authoritative over card-data metadata.

Sources: [Scryfall named-card API](https://api.scryfall.com/docs/cards), [official Commander rules](https://magic.wizards.com/en/formats/commander), [official Commander 1v1](https://magic.wizards.com/en/formats/commander-1v1), and [official banned list](https://magic.wizards.com/en/banned-restricted-list).
