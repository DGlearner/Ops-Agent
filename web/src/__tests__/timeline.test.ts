import { describe, expect, it } from 'vitest'
import type { TimelineEntry } from '@/api/types'
import { buildTimeline } from '@/timeline'
import { ANALYSIS } from './fixtures'

const ENTRIES: TimelineEntry[] = [
  {
    event_id: 'e3',
    type: 'STATUS_CHANGED',
    actor_type: 'USER',
    from_status: 'open',
    to_status: 'resolved',
    payload: { note: 'restarted MySQL' },
    at: '2026-10-17T18:40:00Z',
  },
  { event_id: 'e1', type: 'OPENED', actor_type: 'SYSTEM', payload: { title: 'db down' }, at: '2026-10-17T18:34:57Z' },
  {
    event_id: 'e2',
    type: 'ALERT_ATTACHED',
    actor_type: 'SYSTEM',
    payload: { service: 'order-service', summary: '3 requests failed' },
    at: '2026-10-17T18:34:57.5Z',
  },
]

describe('buildTimeline', () => {
  it('merges the incident history with the analyses, oldest first', () => {
    const items = buildTimeline(ENTRIES, [ANALYSIS])

    expect(items.map((i) => i.title)).toEqual([
      'Incident 创建',
      '告警 · order-service',
      '请求 Agent 分析（系统自动）',
      'Agent 完成分析',
      '值班人员把状态从「未处理」改为「已解决」',
    ])
    expect(items[3]?.detail).toBe('最可能的原因：shop-mysql')
    expect(items[4]).toMatchObject({ detail: 'restarted MySQL', type: 'success' })
  })

  it('shows why an analysis failed', () => {
    const failed = { ...ANALYSIS, status: 'failed' as const, hypotheses: [], error: 'LLM timed out' }

    expect(buildTimeline([], [failed]).at(-1)).toMatchObject({ title: 'Agent 分析失败', detail: 'LLM timed out' })
  })
})
