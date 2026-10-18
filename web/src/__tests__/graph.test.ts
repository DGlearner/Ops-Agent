import { describe, expect, it } from 'vitest'
import { layout } from '@/graph'
import { SHOP } from './fixtures'

describe('layout', () => {
  it('puts callers above what they call, the shared database at the bottom', () => {
    const rows = Object.fromEntries([...layout(SHOP)].map(([name, p]) => [name, p.y]))

    expect(rows['order-service']).toBeLessThan(rows['inventory-service']!)
    expect(rows['inventory-service']).toBe(rows['payment-service'])
    expect(rows['shop-mysql']).toBeGreaterThan(rows['payment-service']!)
  })

  it('centres each row and orders it by name', () => {
    const positions = layout(SHOP)

    expect(positions.get('order-service')?.x).toBe(0)
    expect(positions.get('inventory-service')!.x).toBeLessThan(positions.get('payment-service')!.x)
    expect(positions.get('inventory-service')!.x).toBe(-positions.get('payment-service')!.x)
  })

  it('stops on a cycle and ignores edges to unknown nodes', () => {
    const positions = layout({
      nodes: [
        { name: 'a', kind: 'service' },
        { name: 'b', kind: 'service' },
      ],
      edges: [
        { from: 'a', to: 'b' },
        { from: 'b', to: 'a' },
        { from: 'a', to: 'ghost' },
      ],
    })

    expect([...positions.keys()].sort()).toEqual(['a', 'b'])
  })
})
