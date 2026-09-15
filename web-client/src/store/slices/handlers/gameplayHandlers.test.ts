import { describe, expect, it, vi } from 'vitest'
import { createGameplayHandlers } from './gameplayHandlers'
import type { GetState, SetState } from './types'

describe('gameplay lifecycle handlers', () => {
  it('clears a session announced before a failed match start', () => {
    const setState = vi.fn() as unknown as SetState
    const getState = vi.fn() as unknown as GetState

    createGameplayHandlers(setState, getState).onGameCancelled()

    expect(setState).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: null,
      gameState: null,
      mulliganState: null,
      legalActions: [],
    }))
  })
})
