// What core-service's REST API returns (docs/project-design.md §7.3). Fields in snake_case; a field that is null
// is left out of the response, hence the optional ones.

export type Severity = 'info' | 'low' | 'medium' | 'high' | 'critical'

export type IncidentStatus = 'open' | 'acknowledged' | 'investigating' | 'mitigated' | 'resolved' | 'closed'

export interface Page<T> {
  items: T[]
  /** Absent on the last page. */
  next_cursor?: string
}

export interface Incident {
  incident_id: string
  title: string
  status: IncidentStatus
  severity: Severity
  /** Services whose logs raised the alerts. */
  services: string[]
  /** Services the alerts are about: a failed call from order-service to inventory-service is about the latter. */
  subjects: string[]
  /** What every subject depends on, from the catalog. A hint, not a root cause. */
  shared_dependencies: string[]
  alert_count: number
  opened_at: string
  last_alert_at: string
  acknowledged_at?: string
  resolved_at?: string
  closed_at?: string
  version: number
}

export interface Sample {
  event_id: string
  timestamp: string
  message: string
  trace_id?: string
}

export interface Alert {
  alert_id: string
  rule_id: string
  rule_version: number
  fingerprint: string
  severity: Severity
  group_key: string
  service?: string
  host?: string
  group_values: Record<string, string>
  window_start: string
  window_end: string
  count: number
  threshold: number
  first_event_at?: string
  last_event_at?: string
  summary: string
  sample_event_ids: string[]
  /** The matching log events kept as evidence, a subset of {@link sample_event_ids}. */
  samples: Sample[]
  created_at: string
  stored_at: string
}

export interface TimelineEntry {
  event_id: string
  type: 'OPENED' | 'ALERT_ATTACHED' | 'STATUS_CHANGED'
  actor_type: 'SYSTEM' | 'USER'
  from_status?: IncidentStatus
  to_status?: IncidentStatus
  payload: Record<string, unknown>
  at: string
}

export type AnalysisStatus = 'pending' | 'completed' | 'failed' | 'timed_out'

export type Confidence = 'high' | 'medium' | 'low'

/** What a hypothesis rests on: an alert id, a sample event id, a catalog entry or the incident id. */
export interface Evidence {
  kind: 'alert' | 'event' | 'topology' | 'incident'
  id: string
  note?: string
}

export interface Hypothesis {
  component: string
  cause: string
  confidence: Confidence
  evidence: Evidence[]
}

/** An analysis of an incident by the agent. Hypotheses only cite evidence the incident has; core-service moved
 * anything else the agent cited to {@link rejected_evidence}. */
export interface Analysis {
  analysis_id: string
  incident_id: string
  status: AnalysisStatus
  /** {@code system} (dispatched once the incident settled) or {@code user}. */
  requested_by: string
  requested_at: string
  completed_at?: string
  summary?: string
  uncertain?: boolean
  /** Most likely first. */
  hypotheses?: Hypothesis[]
  next_steps?: string[]
  rejected_evidence?: Evidence[]
  model?: string
  tool_calls?: number
  duration_ms?: number
  error?: string
}

export interface TopologyNode {
  name: string
  kind: 'service' | 'database' | 'cache' | 'queue' | 'external'
  description?: string
}

/** {@code from} depends on {@code to}. */
export interface TopologyEdge {
  from: string
  to: string
}

export interface Topology {
  nodes: TopologyNode[]
  edges: TopologyEdge[]
}

/** RFC 9457 Problem Details with core-service's stable {@code code}, e.g. INCIDENT_NOT_FOUND. */
export interface Problem {
  status: number
  title: string
  detail?: string
  code?: string
}

/** A case: handling one fault from diagnosis to sign-off (docs/agent-design.md §2, §13.1). */
export type CaseStatus =
  | 'open'
  | 'diagnosing'
  | 'mitigating'
  | 'verifying'
  | 'monitoring'
  | 'resolved'
  | 'handed_over'

export interface CaseEntry {
  entry_id: string
  kind: 'statement' | 'observation' | 'fact' | 'hypothesis' | 'constraint' | 'decision' | 'note'
  status: string
  text: string
  source_type: 'user' | 'observation' | 'rule' | 'analyst' | 'model' | 'receipt' | 'verification' | 'system'
  source_ref?: string
  evidence: { kind?: string; ref?: string; id?: string }[]
  data: Record<string, unknown>
  supersedes?: string
  superseded_by?: string
  /** The chat run that wrote it. */
  run_id?: string
  created_at: string
}

export interface CaseEvent {
  event_id: string
  type: string
  actor: string
  payload: Record<string, unknown>
  created_at: string
}

export interface Case {
  case_id: string
  title: string
  status: CaseStatus
  goal?: string
  version: number
  state_version: number
  incident_ids: string[]
  entries: CaseEntry[]
  events: CaseEvent[]
  created_at: string
  updated_at: string
  resolved_at?: string
}

export interface Remediation {
  remediation_id: string
  action_id: string
  case_id: string
  op: string
  params: Record<string, unknown>
  status: 'accepted' | 'executed' | 'failed' | 'blocked' | 'unknown'
  precheck: Record<string, unknown>
  receipt: { summary?: string; observed?: Record<string, unknown>; error?: string; at?: string }
  /** The chat run that proposed it, and the snapshot it decided on (empty when confirmed before the run ended). */
  run_id?: string
  session_id?: string
  snapshot_digest?: string
  created_at: string
  finished_at?: string
}

export interface VerificationCheck {
  id: string
  title: string
  verdict: 'met' | 'not_met' | 'insufficient'
  reason: string
}

export interface Verification {
  verification_id: string
  case_id: string
  remediation_id: string
  run_id?: string
  criteria: { goal: 'recover' | 'contain'; checks: { id: string; title: string }[] }
  window_from: string
  not_before: string
  status: 'pending' | 'leased' | 'done' | 'stale'
  outcome?: 'recovered' | 'not_recovered' | 'inconclusive'
  result?: { checks: VerificationCheck[] }
  completed_at?: string
}
