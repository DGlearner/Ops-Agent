// The chat assistant's API (agent/src/ops_agent/chat/server.py, docs/project-design.md §6.7). The dev server forwards
// /api/v1/chat to it, the rest of /api/v1 to core-service. An answer arrives as Server-Sent Events.

import { ApiError } from './client'
import type { Problem } from './types'

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
  kind?: 'incident' | 'alert' | 'event'
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
}

export interface Trace {
  route?: RouteInfo | null
  items: TraceItem[]
  citations: Citation[]
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
    response.status === 409 ? '上一个问题还在回答中' : p.detail ?? `对话服务返回 HTTP ${response.status}`
  return new ApiError(response.status, p.code, detail)
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
   * Asks, and hands each event to onEvent as it arrives; resolves when the stream ends. Abort to stop. thinking:
   * the model reasons at length before it answers, and its reasoning streams (slower).
   */
  async ask(
    sessionId: string,
    content: string,
    thinking: boolean,
    onEvent: (event: string, data: unknown) => void,
    signal: AbortSignal,
  ): Promise<void> {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
    let response: Response
    try {
      response = await fetch(`/api/v1/chat/sessions/${sessionId}/messages`, {
        method: 'POST',
        headers: { Accept: 'text/event-stream', 'Content-Type': 'application/json' },
        body: JSON.stringify({ content, tz, thinking }),
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
