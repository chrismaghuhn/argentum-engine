import { useCallback, useState } from 'react'
import { useGameStore } from '@/store/gameStore.ts'
import type { EntityId, OrderObjectsDecision } from '@/types'
import type { ResponsiveSizes } from '@/hooks/useResponsive.ts'

interface OrderObjectsUIProps {
  decision: OrderObjectsDecision
  responsive: ResponsiveSizes
}

/**
 * Generic ordering UI for non-combat objects, including simultaneous triggered abilities.
 * Combat blocker ordering keeps its card-aware UI in [OrderBlockersUI].
 */
export function OrderObjectsUI({ decision, responsive }: OrderObjectsUIProps) {
  const [orderedObjects, setOrderedObjects] = useState<EntityId[]>([...decision.objects])
  const submitOrderedDecision = useGameStore((state) => state.submitOrderedDecision)

  const moveObject = useCallback((index: number, direction: 'up' | 'down') => {
    const nextIndex = direction === 'up' ? index - 1 : index + 1
    if (nextIndex < 0 || nextIndex >= orderedObjects.length) return

    setOrderedObjects((current) => {
      const next = [...current]
      const [objectId] = next.splice(index, 1) as [EntityId]
      next.splice(nextIndex, 0, objectId)
      return next
    })
  }, [orderedObjects.length])

  const labelFor = (objectId: EntityId): string =>
    decision.objectLabels?.[objectId] ?? decision.cardInfo?.[objectId]?.name ?? objectId

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.92)',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: responsive.isMobile ? 16 : 24,
        padding: responsive.containerPadding,
        pointerEvents: 'auto',
        zIndex: 1000,
      }}
    >
      <div style={{ textAlign: 'center', maxWidth: 640 }}>
        <h2
          style={{
            color: 'white',
            margin: 0,
            fontSize: responsive.isMobile ? 20 : 28,
            fontWeight: 600,
          }}
        >
          Order Simultaneous Abilities
        </h2>
        <p
          style={{
            color: '#aaa',
            margin: '8px 0 0',
            fontSize: responsive.fontSize.normal,
          }}
        >
          {decision.prompt}
        </p>
        <p
          style={{
            color: '#888',
            margin: '8px 0 0',
            fontSize: responsive.fontSize.small,
          }}
        >
          Move the abilities into the order you want to use when they are put on the stack.
        </p>
      </div>

      <ol
        aria-label="Simultaneous abilities order"
        style={{
          listStyle: 'none',
          margin: 0,
          padding: 0,
          width: 'min(640px, 100%)',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
        }}
      >
        {orderedObjects.map((objectId, index) => (
          <li
            key={objectId}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              padding: '12px 14px',
              backgroundColor: '#171717',
              border: '1px solid #3f3f46',
              borderRadius: 8,
              color: 'white',
            }}
          >
            <span
              style={{
                minWidth: 28,
                color: '#a1a1aa',
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {index + 1}.
            </span>
            <span style={{ flex: 1, lineHeight: 1.35 }}>{labelFor(objectId)}</span>
            <button
              type="button"
              onClick={() => moveObject(index, 'up')}
              disabled={index === 0}
              aria-label={`Move ${labelFor(objectId)} earlier`}
              style={arrowButtonStyle}
            >
              ↑
            </button>
            <button
              type="button"
              onClick={() => moveObject(index, 'down')}
              disabled={index === orderedObjects.length - 1}
              aria-label={`Move ${labelFor(objectId)} later`}
              style={arrowButtonStyle}
            >
              ↓
            </button>
          </li>
        ))}
      </ol>

      <button
        type="button"
        onClick={() => submitOrderedDecision(orderedObjects)}
        style={{
          padding: '10px 24px',
          backgroundColor: '#2563eb',
          color: 'white',
          border: 'none',
          borderRadius: 8,
          fontSize: responsive.fontSize.normal,
          fontWeight: 600,
          cursor: 'pointer',
        }}
      >
        Confirm Order
      </button>
    </div>
  )
}

const arrowButtonStyle = {
  width: 32,
  height: 32,
  border: '1px solid #52525b',
  borderRadius: 6,
  backgroundColor: '#27272a',
  color: 'white',
  fontSize: 18,
  lineHeight: 1,
  cursor: 'pointer',
}
