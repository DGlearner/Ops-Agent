import { describe, expect, it } from 'vitest'
import type { Case, Remediation, Verification } from '@/api/types'
import { applyEvent, newAnswer } from '@/chat'
import { reviewDraft } from '@/casework'

const base: Case = {
  case_id: 'c1',
  title: 'order-service answered with DATABASE_UNAVAILABLE',
  status: 'monitoring',
  version: 7,
  state_version: 5,
  incident_ids: ['i1'],
  created_at: '2026-11-01T10:00:00Z',
  updated_at: '2026-11-01T10:05:00Z',
  events: [],
  entries: [
    { entry_id: 'e1', kind: 'fact', status: 'superseded', text: 'shop-mysql 容器：exited', source_type: 'observation', evidence: [], data: {}, created_at: '' },
    { entry_id: 'e2', kind: 'fact', status: 'active', text: 'shop-mysql 容器：running', source_type: 'observation', evidence: [], data: {}, created_at: '' },
    { entry_id: 'e3', kind: 'hypothesis', status: 'supported', text: 'MySQL 停机', source_type: 'model', evidence: [], data: {}, created_at: '' },
    { entry_id: 'e4', kind: 'statement', status: 'active', text: '用户说重启过', source_type: 'user', evidence: [], data: {}, created_at: '' },
  ],
}

describe('review draft', () => {
  it('lists what the case recorded by source, leaves out what was superseded, and the verdicts', () => {
    const r: Remediation = {
      remediation_id: 'r1', action_id: 'a1', case_id: 'c1', op: 'start_container', params: { name: 'shop-mysql' },
      status: 'executed', precheck: {}, receipt: { summary: '已启动 shop-mysql' }, created_at: '',
    }
    const v: Verification = {
      verification_id: 'v1', case_id: 'c1', remediation_id: 'r1', status: 'done', outcome: 'recovered',
      criteria: { goal: 'recover', checks: [] }, window_from: '', not_before: '',
      result: { checks: [{ id: 'orders_paid', title: '有成功订单', verdict: 'met', reason: 'order_paid 12 次' }] },
    }
    const draft = reviewDraft(base, [r], [v])
    expect(draft).toContain('shop-mysql 容器：running（探针观察）')
    expect(draft).not.toContain('exited')
    expect(draft).toContain('MySQL 停机（模型推断，supported）')
    expect(draft).toContain('用户说重启过（来自用户）')
    expect(draft).toContain('验证：已恢复')
    expect(draft).toContain('✓ 有成功订单：order_paid 12 次')
  })
})

describe('case events', () => {
  it('keep the case an answer worked on with the answer', () => {
    const m = newAnswer()
    applyEvent(m, 'case', { case_id: 'c1', status: 'diagnosing' })
    applyEvent(m, 'case', { case_id: 'c1', entry: 'H1' })
    expect(m.trace?.case).toEqual({ case_id: 'c1', status: 'diagnosing', entry: 'H1' })
  })
})
