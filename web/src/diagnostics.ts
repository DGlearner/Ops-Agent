import type { ActionCard, DiagnosisTask } from './api/chat'
import type { Alert, Analysis, Incident, TimelineEntry } from './api/types'

export interface TaskApi {
  analysis(id: string): Promise<Analysis>
  analyses(id: string): Promise<Analysis[]>
  incident(id: string): Promise<Incident>
  timeline(id: string): Promise<TimelineEntry[]>
  incidentAlerts(id: string): Promise<Alert[]>
}

export interface TaskResult {
  analysis: Analysis
  incident: Incident
  alerts: Alert[]
  stale: boolean
}

/** A confirmed manual reanalysis uses the same task UI; an unconfirmed card grants no task. */
export function confirmedDiagnosis(card: ActionCard): DiagnosisTask | undefined {
  const body = card.receipt?.response?.body
  if (card.kind !== 'request_analysis' || card.status !== 'succeeded' || !body || typeof body !== 'object') return
  const task = body as Partial<Analysis>
  if (!task.analysis_id || task.incident_id !== card.target.incident_id || !task.status
      || !['pending', 'completed', 'failed', 'timed_out'].includes(task.status)) return
  return { analysis_id: task.analysis_id, incident_id: task.incident_id, incident_ref: card.target.ref,
    status: task.status, requested_at: task.requested_at, stale: false,
    evidence_at: task.requested_at ?? '1970-01-01T00:00:00Z' }
}

/** Reads only. A late result stays on its own task; newer requests and late-arriving alerts invalidate it. */
export async function readDiagnosis(task: DiagnosisTask, api: TaskApi): Promise<TaskResult> {
  const [analysis, rows, incident, timeline] = await Promise.all([
    api.analysis(task.analysis_id), api.analyses(task.incident_id),
    api.incident(task.incident_id), api.timeline(task.incident_id),
  ])
  if (analysis.analysis_id !== task.analysis_id || analysis.incident_id !== task.incident_id
      || incident.incident_id !== task.incident_id) {
    throw new Error('任务回执与所问事件不匹配，未展示结论')
  }
  const latest = rows.filter((a) => a.incident_id === task.incident_id)
    .sort((a, b) => Date.parse(b.requested_at) - Date.parse(a.requested_at)
      || b.analysis_id.localeCompare(a.analysis_id))[0]
  const arrivals = timeline.filter((e) => e.type === 'OPENED' || e.type === 'ALERT_ATTACHED').map((e) => Date.parse(e.at))
  const arrived = Math.max(Date.parse(task.evidence_at),
    ...(arrivals.length ? arrivals : [Date.parse(incident.last_alert_at)]))
  const stale = task.stale || !latest || latest.analysis_id !== task.analysis_id
    || !Number.isFinite(Date.parse(analysis.requested_at)) || arrived > Date.parse(analysis.requested_at)
  const alerts = analysis.status === 'completed' ? await api.incidentAlerts(task.incident_id) : []
  return { analysis, incident, alerts, stale }
}
