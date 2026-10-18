import { describe, expect, it } from 'vitest'
import type { IncidentStatus } from '@/api/types'
import { ago, formatSeconds, NEXT_STATUSES } from '@/format'

describe('formatSeconds', () => {
  it('reads like a person would say it', () => {
    expect(formatSeconds(12.4)).toBe('12 秒')
    expect(formatSeconds(185)).toBe('3 分 5 秒')
    expect(formatSeconds(120)).toBe('2 分')
    expect(formatSeconds(7800)).toBe('2 小时 10 分')
    expect(formatSeconds(-3)).toBe('0 秒')
  })
})

describe('ago', () => {
  const now = Date.parse('2026-10-17T12:00:00Z')

  it('counts back from now', () => {
    expect(ago('2026-10-17T11:59:50Z', now)).toBe('刚刚')
    expect(ago('2026-10-17T11:55:00Z', now)).toBe('5 分钟前')
    expect(ago('2026-10-17T09:00:00Z', now)).toBe('3 小时前')
  })
})

describe('NEXT_STATUSES', () => {
  it('follows the lifecycle: never to itself, nothing after closed, reopen only from resolved', () => {
    for (const [from, next] of Object.entries(NEXT_STATUSES) as [IncidentStatus, IncidentStatus[]][]) {
      expect(next).not.toContain(from)
      expect(next.includes('open')).toBe(from === 'resolved')
    }
    expect(NEXT_STATUSES.closed).toEqual([])
  })
})
