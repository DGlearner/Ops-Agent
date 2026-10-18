import { describe, expect, it } from 'vitest'
import { citedIds, evidenceResolver } from '@/evidence'
import { ALERT, ANALYSIS, INCIDENT, SHOP } from './fixtures'

describe('evidenceResolver', () => {
  const resolve = evidenceResolver(INCIDENT, [ALERT], SHOP)

  it('shows a cited log event as its line, in the alert that holds it', () => {
    const e = resolve({ kind: 'event', id: 'event-1', note: 'shutdown' })

    expect(e.title).toBe('Database unavailable: Server shutdown in progress')
    expect(e.source).toContain('order-service')
    expect(e.alertId).toBe('alert-1')
    expect(e.note).toBe('shutdown')
  })

  it('still points to the alert when the event is beyond the samples it keeps', () => {
    const e = resolve({ kind: 'event', id: 'event-2' })

    expect(e.title).toBe('日志事件 event-2')
    expect(e.alertId).toBe('alert-1')
  })

  it('shows an alert by its summary and a catalog entry by its description', () => {
    expect(resolve({ kind: 'alert', id: 'alert-1' })).toMatchObject({ title: ALERT.summary, alertId: 'alert-1' })
    expect(resolve({ kind: 'topology', id: 'shop-mysql' }).source).toBe(SHOP.nodes[3]!.description)
    expect(resolve({ kind: 'incident', id: 'inc-1' }).title).toBe('本 Incident')
  })

  it('names what it cannot find by a short id instead of failing', () => {
    const e = resolve({ kind: 'alert', id: '0123456789abcdef' })

    expect(e.title).toBe('告警 01234567')
    expect(e.alertId).toBeUndefined()
  })
})

describe('citedIds', () => {
  it('collects the alerts and events the hypotheses cite, not catalog entries', () => {
    expect([...citedIds(ANALYSIS.hypotheses)].sort()).toEqual(['alert-1', 'event-1'])
    expect(citedIds(undefined).size).toBe(0)
  })
})
