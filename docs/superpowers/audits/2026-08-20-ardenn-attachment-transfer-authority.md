# #47 Generic Attachment Transfer — Implementation Authority Audit

Recorded at implementation start in the dedicated `pr-47` worktree.

## Git baseline

- `IMPLEMENTATION_BASE`: `470e6d49c541b9af43253a07671d50ae6af268be`
- `FROZEN_BASELINE`: `6ff9ded1403d59ac`
- Branch: `agent/a8-ardenn-attachment-transfer`
- Worktree: `C:\argentum-campaign\pr-47`

`IMPLEMENTATION_BASE` was obtained at runtime after `git fetch origin main` with
`git rev-parse origin/main`. It is recorded independently from the frozen
campaign baseline and must be refreshed if the branch is synchronized again.

## Rules authority

- Live page: <https://magic.wizards.com/en/rules>
- Resolved TXT URL: <https://media.wizards.com/2026/downloads/MagicCompRules%2020260819.txt>
- Effective date stated by the resolved file: `August 7, 2026`
- SHA-256: `4381ad1b39ab2c05f7d03633a20f711ed37277074d3266dcba5f38cbb527423f`

The resolved text was downloaded transiently for verification and is not
committed. The implementation relies on the live-linked URL and this recorded
hash, not on a filename inferred from cache state.

Rules checked before implementation:

- CR 608.2f: multi-object actions are normally processed simultaneously.
- CR 701.3a–d: attach legality, illegal/no-op behavior, and new timestamps for
  a battlefield attachment moved to a different object or player.
- CR 613.7e: an Aura, Equipment, or Fortification receives a new timestamp when
  it becomes attached.
- CR 613.7m: simultaneous timestamp recipients receive a relative order under
  APNAP and the controlling player's choice.
