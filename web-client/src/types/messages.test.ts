import { describe, expect, it } from 'vitest'
import { createStartCurriculumHumanVsEngineAiMessage } from './messages'

describe('Research Arena launch message', () => {
  it('contains only the server-owned command type', () => {
    expect(createStartCurriculumHumanVsEngineAiMessage()).toEqual({
      type: 'startCurriculumHumanVsEngineAi',
    })
  })
})
