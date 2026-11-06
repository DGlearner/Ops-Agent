// The chat assistant's API (agent/src/ops_agent/chat/server.py, docs/project-design.md §6.7). The dev server forwards
// /api/v1/chat to it, the rest of /api/v1 to core-service. An answer arrives as Server-Sent Events.

import { ApiError } from './client'
import type { Case, CaseEntry, Problem, Remediation, Verification } from './types'

export interface ChatSession {
  session_id: string
  title: string
  created_at: string
  updated_at: string
}

export type RouteName = 'ops_diagnosis' | 'ops_query' | 'ops_knowledge' | 'chitchat' | 'out_of_scope'

/** Which way the question went, and who decided: the rules, the model, or the rules' guess when the model failed. */
export interface RouteInfo {
  route: RouteName
  label: string
  method: 'rule' | 'model' | 'fallback'
  reason: string
  /** Going on from a questionnaire: the route decided when the question was first asked. */
  resumed?: boolean
  entities: { services: string[]; minutes?: number | null; incident_ids: string[]; follow_up: boolean }
  duration_ms: number
}

export interface StepItem {
  type: 'step'
  id: string
  /** locate, fetch, tool, check, fallback */
  kind: string
  title: string
  detail?: string
  status: 'running' | 'done' | 'error'
  result?: string
  duration_ms?: number
}

/** The model's reasoning; interim: text it wrote as an answer before deciding to look further. */
export interface ThoughtItem {
  type: 'thought'
  text: string
  interim?: boolean
}

export type TraceItem = StepItem | ThoughtItem

/** A ref the answer cites ([A1]); verified false when it is not in what was fetched. */
export interface Citation {
  ref: string
  verified: boolean
  kind?: 'incident' | 'alert' | 'event' | 'probe'
  id?: string
  label?: string
  incident_id?: string | null
}

export interface Metrics {
  duration_ms: number
  first_event_ms?: number | null
  first_answer_ms?: number | null
  tool_calls: number
  degraded: boolean
  model?: string | null
  /** The user asked for 深度思考; null: the server's default. */
  thinking?: boolean | null
  /** Older answers have neither the run nor its account. */
  run_id?: string
  total_tokens?: number | null
  calls_without_usage?: number
}

/** One model call or graph step of a run (docs/agent-design.md §15.1). */
export interface UsageSpan {
  kind: 'model' | 'step'
  name: string
  at_ms: number
  duration_ms: number | null
  status: 'running' | 'ok' | 'error' | 'cancelled'
  tier?: 'main' | 'quick'
  purpose?: string
  step_kind?: string
  first_token_ms?: number
  prompt_tokens?: number
  completion_tokens?: number
  error?: string
}

export interface RunUsage {
  model_calls: number
  model_ms: number
  prompt_tokens: number | null
  completion_tokens: number | null
  cached_tokens: number | null
  reasoning_tokens: number | null
  total_tokens: number | null
  calls_without_usage: number
  tool_steps: number
  tool_ms: number
  wall_ms: number
  spans: UsageSpan[]
}

export type RunStatus = 'running' | 'answered' | 'paused_question' | 'paused_action' | 'degraded' | 'failed' | 'cancelled'

/** A run from the request to the verdict (docs/agent-design.md §15.2). */
export interface RunChain {
  run: {
    run_id: string
    session_id: string
    request_id?: string | null
    trigger: 'user_message' | 'questionnaire_reply'
    status: RunStatus
    case_id?: string | null
    snapshot?: Record<string, unknown> | null
    snapshot_digest?: string | null
    usage?: RunUsage | null
    started_at: string
    ended_at?: string | null
  }
  question?: string | null
  answer?: { content: string; status: string; created_at: string } | null
  actions: (ActionCard & { remediation?: Remediation | null; verifications: Verification[] })[]
  cases: { case_id: string; title: string; status: Case['status']; state_version: number; entries: CaseEntry[] }[]
  /** core-service did not answer: what it holds is missing, the rest is here. */
  core_error?: string | null
}

export type ActionStatus =
  | 'proposed'
  | 'executing'
  | 'succeeded'
  | 'failed'
  | 'blocked'
  | 'declined'
  | 'expired'
  | 'unknown'

/** What happened when the card was decided on: core-service's answer as it was, and the incident read back. */
export interface Receipt {
  receipt_id?: string
  status: ActionStatus
  /** false: not run at all; null: not known whether it took effect. */
  executed: boolean | null
  decision?: 'confirm' | 'decline'
  confirmed_version?: number
  summary: string
  checks: string[]
  at: string
  request?: { method: string; path: string; body: unknown }
  response?: { status: number; code?: string | null; body?: unknown; error?: string }
  observed?: { status: string; version: number }
  /** A remediation: core-service's run of it, what it checked and read back (docs/agent-design.md §5.3). */
  remediation?: {
    remediation_id?: string
    status?: string
    precheck?: Record<string, unknown>
    observed?: Record<string, unknown>
  }
}

/** An action the model proposed; only the user's confirm (at this version) runs it (chat/actions.py). */
export interface ActionCard {
  action_id: string
  session_id: string
  message_id: string
  kind: 'transition_incident' | 'request_analysis' | 'start_container' | 'kill_transaction' | 'set_switch'
  status: ActionStatus
  version: number
  title: string
  params: Record<string, unknown>
  risk: 'low' | 'medium' | 'high'
  reason: string
  target: { ref: string; incident_id: string; title: string; status: string; version: number }
  evidence: { ref: string; kind: 'incident' | 'alert' | 'event' | 'probe'; id: string; label: string }[]
  receipt: Receipt | null
  /** Changes the system being watched: proposed from a runbook, confirmed with its digest. */
  remedial?: boolean
  case?: { case_id: string; state_version: number; status: string }
  runbook?: { id: string; version: number }
  preconditions?: string[]
  effect?: string
  blast_radius?: string
  rollback?: string
  verify?: string
  payload_digest?: string
  created_at: string
  updated_at: string
  expires_at: string
}

export interface QuestionOption {
  id: string
  label: string
  detail?: string
  incident_id?: string
}

export interface Question {
  id: string
  title: string
  kind: 'single' | 'multiple'
  options: QuestionOption[]
  allow_text: boolean
}

/** The questions an answer ended with; the next message goes on from there (chat/clarify.py). */
export interface Questionnaire {
  pause_id: string
  source: 'orchestration' | 'model'
  questions: Question[]
  expires_at: string
  message_id?: string
}

export interface QuestionAnswer {
  id: string
  choices: string[]
  text?: string
}

/** Experience for every session, once a person approved it (chat/knowledge.py). */
export interface MemoryCard {
  memory_id: string
  status: 'candidate' | 'approved' | 'rejected'
  version: number
  text: string
  components: string[]
  evidence: { ref: string; kind: string; id: string; label: string; incident_id?: string | null }[]
  /** from_logs: rests on log lines; instruction_like: reads like an instruction. */
  flags: string[]
  source_session: string
  created_at: string
  reviewed_at?: string | null
}

export interface Trace {
  route?: RouteInfo | null
  items: TraceItem[]
  citations: Citation[]
  /** The cards as they were proposed; their current state comes from the session's actions. */
  actions?: ActionCard[]
  memories?: MemoryCard[]
  questionnaire?: Questionnaire | null
  /** The case this answer worked on (docs/agent-design.md §2). */
  case?: { case_id: string; status?: string } | null
  error?: string
  metrics?: Metrics
}

export type MessageStatus = 'streaming' | 'completed' | 'failed' | 'cancelled'

export interface ChatMessage {
  message_id: string
  role: 'user' | 'assistant'
  content: string
  status: MessageStatus
  trace?: Trace | null
  created_at: string
}

export interface SessionDetail extends ChatSession {
  messages: ChatMessage[]
  actions: ActionCard[]
  /** The questionnaire still waiting for an answer, if any. */
  pause: Questionnaire | null
}

export interface SseEvent {
  event: string
  data: string
}

/** Server-Sent Events from a byte stream, fed in whatever pieces the network delivers. */
export class SseParser {
  private buffer = ''

  feed(chunk: string): SseEvent[] {
    this.buffer += chunk.replace(/\r\n?/g, '\n')
    const events: SseEvent[] = []
    let end: number
    while ((end = this.buffer.indexOf('\n\n')) >= 0) {
      const block = this.buffer.slice(0, end)
      this.buffer = this.buffer.slice(end + 2)
      let event = 'message'
      const data: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith(':')) {
          continue // a comment: the server's keep-alive ping
        }
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'event') {
          event = value
        } else if (field === 'data') {
          data.push(value)
        }
      }
      if (data.length) {
        events.push({ event, data: data.join('\n') })
      }
    }
    return events
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/v1/chat${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}) },
    })
  } catch {
    throw new ApiError(0, undefined, '连不上对话服务，请检查 ops-agent chat 是否在运行')
  }
  if (!response.ok) {
    throw await problem(response)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

async function problem(response: Response): Promise<ApiError> {
  let p: Partial<Problem> = {}
  try {
    p = (await response.json()) as Partial<Problem>
  } catch {
    // The dev server answers on its own when the chat server is not running.
  }
  const detail =
    p.code === 'SESSION_BUSY' || (response.status === 409 && !p.detail)
      ? '上一个问题还在回答中'
      : p.detail ?? `对话服务返回 HTTP ${response.status}`
  return new ApiError(response.status, p.code, detail)
}

/** What the page the question is asked from shows: only to tell what "这个" is. */
export interface PageContext {
  incident_id?: string
  case_id?: string
  case_version?: number
}

export function newRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `req-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export const chatApi = {
  sessions(): Promise<{ items: ChatSession[] }> {
    return request('/sessions')
  },
  createSession(): Promise<ChatSession> {
    return request('/sessions', { method: 'POST' })
  },
  session(id: string): Promise<SessionDetail> {
    return request(`/sessions/${id}`)
  },
  deleteSession(id: string): Promise<void> {
    return request(`/sessions/${id}`, { method: 'DELETE' })
  },
  /**
   * Runs the card, once; version: the one the user saw, payloadDigest: the digest of what it showed (a remediation
   * runs only with it). A 409 means the card moved on: reload it.
   */
  confirmAction(sessionId: string, actionId: string, version: number, payloadDigest?: string): Promise<ActionCard> {
    return request(`/sessions/${sessionId}/actions/${actionId}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ version, payload_digest: payloadDigest }),
    })
  },
  declineAction(sessionId: string, actionId: string, version: number): Promise<ActionCard> {
    return request(`/sessions/${sessionId}/actions/${actionId}/decline`, {
      method: 'POST',
      body: JSON.stringify({ version }),
    })
  },
  actions(sessionId: string): Promise<{ items: ActionCard[] }> {
    return request(`/sessions/${sessionId}/actions`)
  },
  run(runId: string): Promise<RunChain> {
    return request(`/runs/${runId}`)
  },
  memories(status?: MemoryCard['status']): Promise<{ items: MemoryCard[] }> {
    return request(`/memories${status ? `?status=${status}` : ''}`)
  },
  reviewMemory(memoryId: string, version: number, approve: boolean): Promise<MemoryCard> {
    return request(`/memories/${memoryId}/${approve ? 'approve' : 'reject'}`, {
      method: 'POST',
      body: JSON.stringify({ version }),
    })
  },
  /**
   * Asks, and hands each event to onEvent as it arrives; resolves when the stream ends. Abort to stop. thinking:
   * the model reasons at length before it answers, and its reasoning streams (slower). reply: answers to the
   * questionnaire the last answer ended with, instead of a new question.
   */
  async ask(
    sessionId: string,
    content: string,
    thinking: boolean,
    onEvent: (event: string, data: unknown) => void,
    signal: AbortSignal,
    reply?: { pause_id: string; answers: QuestionAnswer[] },
    page?: PageContext,
  ): Promise<void> {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
    // Sent again (a retry), the same request gets the answer it already had, not a second one.
    const request_id = newRequestId()
    let response: Response
    try {
      response = await fetch(`/api/v1/chat/sessions/${sessionId}/messages`, {
        method: 'POST',
        headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json' },
        body: JSON.stringify(reply ? { reply, tz, thinking, request_id, page } : { content, tz, thinking, request_id, page }),
        signal,
      })
    } catch {
      if (signal.aborted) {
        return
      }
      throw new ApiError(0, undefined, '连不上对话服务，请检查 ops-agent chat 是否在运行')
    }
    if (!response.ok || !response.body) {
      throw await problem(response)
    }
    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
    const parser = new SseParser()
    try {
      for (;;) {
        const { value, done } = await reader.read()
        if (done) {
          break
        }
        for (const e of parser.feed(value)) {
          onEvent(e.event, JSON.parse(e.data))
        }
      }
    } catch {
      if (!signal.aborted) {
        throw new ApiError(0, undefined, '回答的连接中断了')
      }
    }
  },
}
