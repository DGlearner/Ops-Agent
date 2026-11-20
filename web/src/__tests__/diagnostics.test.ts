import { describe, expect, it, vi } from 'vitest'
import { confirmedDiagnosis, readDiagnosis, type TaskApi } from '@/diagnostics'
import { applyEvent, newAnswer } from '@/chat'
import type { ActionCard, DiagnosisTask } from '@/api/chat'
import type { Analysis } from '@/api/types'
import { INCIDENT, ALERT } from './fixtures'

const task: DiagnosisTask = {
  incident_id: INCIDENT.incident_id, incident_ref: 'I1', analysis_id: 'task-1', status: 'pending',
  requested_at: '2026-10-17T18:36:00Z', evidence_at: INCIDENT.last_alert_at, stale: false,
}

function setup(status: Analysis['status'] = 'pending') {
  const analysis: Analysis = {
    analysis_id: task.analysis_id, incident_id: task.incident_id, status,
    requested_at: task.requested_at!, requested_by: 'system', summary: status === 'completed' ? '数据库不可用' : undefined,
    hypotheses: [{ component: 'shop-mysql', cause: '连接失败', confidence: 'medium',
      evidence: [{ kind: 'alert', id: ALERT.alert_id }] }],
  }
  const api: TaskApi = {
    analysis: vi.fn(async () => analysis), analyses: vi.fn(async () => [analysis]),
    incident: vi.fn(async () => INCIDENT), timeline: vi.fn(async () => []),
    incidentAlerts: vi.fn(async () => [ALERT]),
  }
  return { api, analysis }
}

describe('background diagnosis in chat', () => {
  it('tracks a manual task only after a successful matching confirmation receipt', () => {
    const card: ActionCard = {
      action_id: 'a', session_id: 's', message_id: 'm', kind: 'request_analysis', status: 'proposed',
      version: 1, title: '重新分析', params: {}, risk: 'low', reason: '用户请求',
      target: { ref: 'I1', incident_id: task.incident_id, title: 't', status: 'open', version: 1 },
      evidence: [], receipt: null, created_at: task.requested_at!, updated_at: task.requested_at!,
      expires_at: '2026-10-17T19:00:00Z',
    }
    expect(confirmedDiagnosis(card)).toBeUndefined()
    card.status = 'succeeded'
    card.receipt = { status: 'succeeded', executed: true, summary: '派发完成', checks: [], at: task.requested_at!,
      response: { status: 202, body: { analysis_id: task.analysis_id, incident_id: task.incident_id, status: 'pending' } } }
    expect(confirmedDiagnosis(card)?.analysis_id).toBe(task.analysis_id)
    card.receipt.response!.body = { analysis_id: 'wrong', incident_id: 'inc-2', status: 'pending' }
    expect(confirmedDiagnosis(card)).toBeUndefined()
  })
  it('replaces the incident task on repeated stream events and keeps separate incidents', () => {
    const message = newAnswer()
    applyEvent(message, 'analysis', { ...task })
    applyEvent(message, 'analysis', { ...task, status: 'completed' })
    applyEvent(message, 'analysis', { ...task, incident_id: 'inc-2', analysis_id: 'task-2' })
    expect(message.trace?.analyses).toHaveLength(2)
    expect(message.trace?.analyses?.[0]?.status).toBe('completed')
  })

  it.each(['pending', 'completed', 'failed', 'timed_out'] as const)('reads the actual %s state without any writes', async (status) => {
    const { api } = setup(status)
    const found = await readDiagnosis(task, api)
    expect(found.analysis.status).toBe(status)
    expect(found.stale).toBe(false)
    expect(api.analysis).toHaveBeenCalledWith('task-1')
    expect(api.analyses).toHaveBeenCalledTimes(1)
    if (status === 'completed') expect(found.alerts[0]?.alert_id).toBe(ALERT.alert_id)
    else expect(api.incidentAlerts).not.toHaveBeenCalled()
  })

  it('follows a pending task to completion without changing identity', async () => {
    const { api, analysis } = setup()
    expect((await readDiagnosis(task, api)).analysis.status).toBe('pending')
    analysis.status = 'completed'
    expect((await readDiagnosis(task, api)).analysis.analysis_id).toBe(task.analysis_id)
  })

  it('marks an old completion as historical when a newer task is pending', async () => {
    const { api, analysis } = setup('completed')
    api.analyses = vi.fn(async () => [{ ...analysis, analysis_id: 'task-2', status: 'pending' as const,
      requested_at: '2026-10-17T18:37:00Z' }, analysis])
    expect((await readDiagnosis(task, api)).stale).toBe(true)
  })

  it('invalidates a result after a late alert arrival even when last_alert_at did not change', async () => {
    const { api } = setup('completed')
    api.timeline = vi.fn(async () => [{ event_id: 'attached', type: 'ALERT_ATTACHED' as const, actor_type: 'SYSTEM' as const,
      payload: {}, at: '2026-10-17T18:38:00Z' }])
    expect((await readDiagnosis(task, api)).stale).toBe(true)
  })

  it('does not let a status transition alone invalidate the result', async () => {
    const { api } = setup('completed')
    api.timeline = vi.fn(async () => [{ event_id: 'moved', type: 'STATUS_CHANGED' as const, actor_type: 'USER' as const,
      payload: {}, at: '2026-10-17T18:38:00Z' }])
    expect((await readDiagnosis(task, api)).stale).toBe(false)
  })

  it.each(['incident', 'task'])('rejects a response for the wrong %s', async (wrong) => {
    const { api, analysis } = setup('completed')
    if (wrong === 'incident') analysis.incident_id = 'inc-2'
    else analysis.analysis_id = 'task-2'
    await expect(readDiagnosis(task, api)).rejects.toThrow('不匹配')
    expect(api.incidentAlerts).not.toHaveBeenCalled()
  })

  it('keeps a read failure visible instead of treating it as completion', async () => {
    const { api } = setup()
    api.analysis = vi.fn(async () => { throw new Error('core unavailable') })
    await expect(readDiagnosis(task, api)).rejects.toThrow('core unavailable')
  })
})
