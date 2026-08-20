import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import type { ResponsiveSizes } from '@/hooks/useResponsive.ts'
import { entityId, type OrderObjectsDecision } from '@/types'
import { OrderObjectsUI } from './OrderObjectsUI'

vi.mock('@/store/gameStore.ts', () => ({
  useGameStore: (selector: (state: { submitOrderedDecision: () => void }) => unknown) =>
    selector({ submitOrderedDecision: () => undefined }),
}))

const responsive = {
  isMobile: false,
  containerPadding: 24,
  fontSize: { small: 12, normal: 16 },
} as ResponsiveSizes

function decision(overrides: Partial<OrderObjectsDecision> = {}): OrderObjectsDecision {
  return {
    type: 'OrderObjectsDecision',
    id: 'decision-1',
    playerId: entityId('player-1'),
    prompt: 'Choose an order',
    context: { phase: 'TRIGGER' },
    objects: [entityId('runtime-entity-1'), entityId('runtime-entity-2')],
    ...overrides,
  }
}

describe('OrderObjectsUI', () => {
  it('uses neutral positional labels when a legacy projection has no labels or card info', () => {
    const html = renderToStaticMarkup(<OrderObjectsUI decision={decision()} responsive={responsive} />)

    expect(html).toContain('Object 1')
    expect(html).toContain('Object 2')
    expect(html).not.toContain('runtime-entity-1')
    expect(html).not.toContain('runtime-entity-2')
  })

  it('preserves server-projected labels for semantic trigger handles', () => {
    const first = entityId('runtime-entity-1')
    const html = renderToStaticMarkup(
      <OrderObjectsUI
        decision={decision({ objectLabels: { [first]: 'Source: ability (simultaneous instance 1 of 2)' } })}
        responsive={responsive}
      />,
    )

    expect(html).toContain('Source: ability (simultaneous instance 1 of 2)')
    expect(html).toContain('Object 2')
    expect(html).not.toContain('runtime-entity-1')
    expect(html).not.toContain('runtime-entity-2')
  })
})
