import type { Alert, Evidence, Hypothesis, Incident, Sample, Topology } from './api/types'
import { formatTime, shortId } from './format'

export const EVIDENCE_KIND_LABEL: Record<Evidence['kind'], string> = {
  alert: '告警',
  event: '日志',
  topology: '拓扑',
  incident: 'Incident',
}

/** A piece of evidence with what its id refers to, looked up in the incident. */
export interface ResolvedEvidence extends Evidence {
  /** The alert's summary, the log line, the catalog entry. */
  title: string
  /** Where it comes from, e.g. "order-service · 10/17 18:34:57". */
  source?: string
  /** The alert on the page that holds it, to scroll to. */
  alertId?: string
}

export type EvidenceResolver = (evidence: Evidence) => ResolvedEvidence

/**
 * Looks up what the agent cited. core-service already dropped evidence the incident does not have, so a miss here
 * means only that the page lacks the details (e.g. an event id beyond the samples an alert keeps).
 */
export function evidenceResolver(incident: Incident, alerts: Alert[], topology?: Topology): EvidenceResolver {
  const alertsById = new Map(alerts.map((a) => [a.alert_id, a]))
  const events = new Map<string, { alert: Alert; sample?: Sample }>()
  for (const alert of alerts) {
    for (const id of alert.sample_event_ids) {
      events.set(id, { alert })
    }
    for (const sample of alert.samples) {
      events.set(sample.event_id, { alert, sample })
    }
  }
  const nodes = new Map((topology?.nodes ?? []).map((n) => [n.name, n]))

  return (evidence) => {
    switch (evidence.kind) {
      case 'alert': {
        const alert = alertsById.get(evidence.id)
        return alert
          ? { ...evidence, title: alert.summary, source: alertSource(alert), alertId: alert.alert_id }
          : { ...evidence, title: `告警 ${shortId(evidence.id)}` }
      }
      case 'event': {
        const found = events.get(evidence.id)
        if (!found) {
          return { ...evidence, title: `日志事件 ${shortId(evidence.id)}` }
        }
        const { alert, sample } = found
        return {
          ...evidence,
          title: sample?.message ?? `日志事件 ${shortId(evidence.id)}`,
          source: [alert.service ?? alert.group_key, sample && formatTime(sample.timestamp)].filter(Boolean).join(' · '),
          alertId: alert.alert_id,
        }
      }
      case 'topology': {
        const node = nodes.get(evidence.id)
        return { ...evidence, title: evidence.id, source: node?.description ?? '服务目录' }
      }
      case 'incident':
        return { ...evidence, title: '本 Incident', source: incident.title }
    }
  }
}

function alertSource(alert: Alert): string {
  return `${alert.service ?? alert.group_key} · ${formatTime(alert.created_at)}`
}

/** The alert and event ids the hypotheses cite, so the page can mark them. */
export function citedIds(hypotheses: Hypothesis[] | undefined): Set<string> {
  return new Set(
    (hypotheses ?? []).flatMap((h) => h.evidence.filter((e) => e.kind === 'alert' || e.kind === 'event').map((e) => e.id)),
  )
}
