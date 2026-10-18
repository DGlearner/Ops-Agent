import type { Alert, Analysis, Incident, Topology } from '@/api/types'

/** The shop's catalog, as core-service seeds it. */
export const SHOP: Topology = {
  nodes: [
    { name: 'inventory-service', kind: 'service', description: 'Shop: stock and reservations' },
    { name: 'order-service', kind: 'service', description: 'Shop: places orders' },
    { name: 'payment-service', kind: 'service', description: 'Shop: simulated payment provider' },
    { name: 'shop-mysql', kind: 'database', description: 'Shop: the MySQL instance of all three services' },
  ],
  edges: [
    { from: 'inventory-service', to: 'shop-mysql' },
    { from: 'order-service', to: 'inventory-service' },
    { from: 'order-service', to: 'payment-service' },
    { from: 'order-service', to: 'shop-mysql' },
    { from: 'payment-service', to: 'shop-mysql' },
  ],
}

export const INCIDENT: Incident = {
  incident_id: 'inc-1',
  title: 'order-service answered 3 requests with DATABASE_UNAVAILABLE within 1m',
  status: 'open',
  severity: 'high',
  services: ['order-service'],
  subjects: ['order-service'],
  shared_dependencies: ['shop-mysql'],
  alert_count: 1,
  opened_at: '2026-10-17T18:34:57Z',
  last_alert_at: '2026-10-17T18:34:57Z',
  version: 0,
}

export const ALERT: Alert = {
  alert_id: 'alert-1',
  rule_id: 'shop-database-unavailable',
  rule_version: 1,
  fingerprint: 'shop-database-unavailable|service=order-service',
  severity: 'high',
  group_key: 'order-service',
  service: 'order-service',
  host: 'shop-host',
  group_values: { service: 'order-service' },
  window_start: '2026-10-17T18:33:55Z',
  window_end: '2026-10-17T18:34:55Z',
  count: 3,
  threshold: 3,
  summary: INCIDENT.title,
  sample_event_ids: ['event-1', 'event-2'],
  samples: [
    { event_id: 'event-1', timestamp: '2026-10-17T18:34:55Z', message: 'Database unavailable: Server shutdown in progress' },
  ],
  created_at: '2026-10-17T18:34:57Z',
  stored_at: '2026-10-17T18:34:57Z',
}

export const ANALYSIS: Analysis = {
  analysis_id: 'analysis-1',
  incident_id: 'inc-1',
  status: 'completed',
  requested_by: 'system',
  requested_at: '2026-10-17T18:36:11Z',
  completed_at: '2026-10-17T18:36:23Z',
  summary: 'shop-mysql is shutting down.',
  uncertain: false,
  hypotheses: [
    {
      component: 'shop-mysql',
      cause: 'MySQL is shutting down',
      confidence: 'high',
      evidence: [
        { kind: 'event', id: 'event-1', note: 'Server shutdown in progress' },
        { kind: 'alert', id: 'alert-1' },
        { kind: 'topology', id: 'shop-mysql' },
      ],
    },
  ],
  next_steps: ['Check shop-mysql'],
  rejected_evidence: [],
  model: 'deepseek-v4-pro',
  tool_calls: 5,
  duration_ms: 12316,
}
