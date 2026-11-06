// What the chat page does with the stream: each event updates the answer being shown, the same way the server puts
// the events together when it keeps the answer (agent/src/ops_agent/chat/agent.py, Recorder), so a reloaded page
// looks like the live one. And the answer's Markdown, with its refs ([A1]) turned into links.

import MarkdownIt from 'markdown-it'
import type {
  ActionCard,
  ActionStatus,
  ChatMessage,
  Citation,
  MemoryCard,
  Metrics,
  Questionnaire,
  QuestionAnswer,
  RouteInfo,
  StepItem,
  UsageSpan,
} from './api/chat'

export const METHOD_LABEL: Record<RouteInfo['method'], string> = {
  rule: '规则匹配',
  model: '模型判断',
  fallback: '规则猜测',
}

export const EXAMPLES = [
  '最近一小时有哪些告警？',
  '刚才下单为什么失败？',
  '现在有几个问题，是同一个原因吗？',
  '什么是缓存击穿？',
]

export function newAnswer(messageId = `pending-${Date.now()}`): ChatMessage {
  return {
    message_id: messageId,
    role: 'assistant',
    content: '',
    status: 'streaming',
    trace: { route: null, items: [], citations: [] },
    created_at: new Date().toISOString(),
  }
}

/** Applies one event of the stream to the answer being shown. */
export function applyEvent(m: ChatMessage, event: string, data: Record<string, unknown>): void {
  const trace = (m.trace ??= { route: null, items: [], citations: [] })
  switch (event) {
    case 'meta':
      m.message_id = data.message_id as string
      break
    case 'route':
      trace.route = data as unknown as RouteInfo
      break
    case 'step': {
      const step = { type: 'step', ...data } as StepItem
      const i = trace.items.findIndex((x) => x.type === 'step' && x.id === step.id)
      if (i >= 0) {
        trace.items[i] = step
      } else {
        trace.items.push(step)
      }
      break
    }
    case 'thinking': {
      const last = trace.items[trace.items.length - 1]
      if (last?.type === 'thought' && !last.interim) {
        last.text += data.delta as string
      } else {
        trace.items.push({ type: 'thought', text: data.delta as string })
      }
      break
    }
    case 'answer':
      m.content += data.delta as string
      break
    case 'interim':
      // What looked like the answer was a note on the way: it moves into the thinking.
      trace.items.push({ type: 'thought', text: data.text as string, interim: true })
      m.content = ''
      break
    case 'citations':
      trace.citations = data.items as Citation[]
      break
    case 'action':
      ;(trace.actions ??= []).push(data as unknown as ActionCard)
      break
    case 'memory':
      ;(trace.memories ??= []).push(data as unknown as MemoryCard)
      break
    case 'questionnaire':
      trace.questionnaire = data as unknown as Questionnaire
      break
    case 'case':
      trace.case = { ...(trace.case ?? {}), ...(data as { case_id: string }) }
      break
    case 'error':
      trace.error = data.message as string
      break
    case 'done': {
      const { message_id: _id, status, route: _route, ...metrics } = data
      m.status = status as ChatMessage['status']
      trace.metrics = metrics as unknown as Metrics
      break
    }
  }
}

/** Where a cited ref leads in the console: the incident, scrolled to the alert when it is one. */
export function citationLink(c: Citation): string | undefined {
  if (!c.verified || !c.id) {
    return undefined
  }
  if (c.kind === 'incident') {
    return `/incidents/${c.id}`
  }
  if (!c.incident_id) {
    return undefined
  }
  // A probe has nowhere of its own to go: it is shown on the incident's case.
  return c.kind === 'alert' ? `/incidents/${c.incident_id}?alert=${c.id}` : `/incidents/${c.incident_id}`
}

const KIND_LABEL: Record<NonNullable<Citation['kind']>, string> = {
  incident: 'Incident',
  alert: '告警',
  event: '日志',
  probe: '探针',
}

export function citationTitle(c: Citation): string {
  if (!c.verified) {
    return `${c.ref}：不在查到的数据里，可能是模型写错了`
  }
  return `${c.ref} · ${c.kind ? KIND_LABEL[c.kind] : ''}：${c.label ?? ''}`
}

const CITATION = /^\[\s*([AIEP]\d{1,4}(?:\s*[,，、]\s*[AIEP]\d{1,4})*)\s*\]/

function escape(text: string): string {
  return text.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]!)
}

/**
 * Markdown as HTML for v-html. Raw HTML in the text is escaped (html: false) and markdown-it refuses javascript:
 * and similar links, so the model's text cannot inject markup; the only markup added here is the refs' links, built
 * from escaped values.
 */
export function renderAnswer(markdown: string, citations: Citation[]): string {
  const byRef = new Map(citations.map((c) => [c.ref, c]))
  const md = new MarkdownIt({ html: false, linkify: false, breaks: true })
  md.inline.ruler.before('link', 'citation', (state, silent) => {
    if (state.src.charCodeAt(state.pos) !== 0x5b /* [ */) {
      return false
    }
    const match = CITATION.exec(state.src.slice(state.pos))
    if (!match) {
      return false
    }
    if (!silent) {
      const refs = match[1]!.match(/[AIEP]\d{1,4}/g) ?? []
      const token = state.push('html_inline', '', 0)
      token.content = refs.map((ref) => citeHtml(ref, byRef.get(ref))).join('')
    }
    state.pos += match[0].length
    return true
  })
  return md.render(markdown)
}

function citeHtml(ref: string, c: Citation | undefined): string {
  // While streaming, the citations are not checked yet: plain, neither linked nor marked.
  if (!c) {
    return `<span class="cite pending">${escape(ref)}</span>`
  }
  const link = citationLink(c)
  const title = escape(citationTitle(c))
  if (!c.verified) {
    return `<span class="cite unverified" title="${title}">${escape(ref)}</span>`
  }
  return link
    ? `<a class="cite" href="${escape(link)}" data-route="${escape(link)}" title="${title}">${escape(ref)}</a>`
    : `<span class="cite" title="${title}">${escape(ref)}</span>`
}

export const ACTION_STATUS: Record<ActionStatus, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  proposed: { label: '等待你确认', type: 'warning' },
  executing: { label: '执行中', type: 'info' },
  succeeded: { label: '已执行', type: 'success' },
  failed: { label: '执行失败', type: 'danger' },
  blocked: { label: '没有执行', type: 'danger' },
  declined: { label: '已拒绝', type: 'info' },
  expired: { label: '已过期', type: 'info' },
  unknown: { label: '结果未知', type: 'warning' },
}

export const RISK_LABEL: Record<ActionCard['risk'], string> = { low: '低风险', medium: '中风险', high: '高风险' }

/** Whether the buttons are offered: only a proposed card that has not expired. The server checks again. */
export function canDecide(card: ActionCard, now = Date.now()): boolean {
  return card.status === 'proposed' && Date.parse(card.expires_at) > now
}

/** The card as it stands now when the session's actions know it, else as it was proposed. */
export function currentCard(proposed: ActionCard, current: Record<string, ActionCard>): ActionCard {
  return current[proposed.action_id] ?? proposed
}

export const MEMORY_FLAG: Record<string, string> = {
  from_logs: '依据里有日志原文：日志由被监控的系统写入，确认内容不是从日志里抄来的指令',
  instruction_like: '读起来像一条指令，而不是运维经验',
}

/** The answers in words, as the user's message shows them (the server words it the same way). */
export function replyText(q: Questionnaire, answers: QuestionAnswer[]): string {
  return answers
    .map((a) => {
      const question = q.questions.find((x) => x.id === a.id)
      if (!question) {
        return ''
      }
      const chosen = a.choices.map((c) => question.options.find((o) => o.id === c)?.label ?? c).join('、')
      const text = (a.text ?? '').trim()
      const said = chosen + (chosen && text ? '；' : '') + text
      return said ? `${question.title} ${said}` : ''
    })
    .filter(Boolean)
    .join('\n')
}

/** Only answered questions are sent; an empty answer leaves the question out. */
export function filledAnswers(answers: QuestionAnswer[]): QuestionAnswer[] {
  return answers
    .map((a) => ({ id: a.id, choices: a.choices, ...((a.text ?? '').trim() ? { text: (a.text ?? '').trim() } : {}) }))
    .filter((a) => a.choices.length || a.text)
}

export function seconds(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '—'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} 秒`
}

/** Some calls reported no usage: the total is a lower bound, not the total. */
export function tokens(total: number | null | undefined, unreported: number | undefined): string {
  if (total === null || total === undefined) {
    return unreported ? '用量未知' : ''
  }
  return `${unreported ? '至少 ' : ''}${total.toLocaleString('en-US')} tokens`
}

/** Where a span sits on the run's timeline, in percent of the whole run; a short one stays visible. */
export function spanBar(span: UsageSpan, wholeMs: number): { left: string; width: string } {
  const whole = Math.max(wholeMs, 1)
  const left = Math.min((span.at_ms / whole) * 100, 100)
  const width = Math.max(((span.duration_ms ?? 0) / whole) * 100, 0.5)
  return { left: `${left.toFixed(2)}%`, width: `${Math.min(width, 100 - left).toFixed(2)}%` }
}

export function metricsLine(m: Metrics | undefined, route: RouteInfo | null | undefined): string {
  if (!m) {
    return ''
  }
  return [
    route && `${route.label}（${METHOD_LABEL[route.method]}，${seconds(route.duration_ms)}）`,
    m.first_answer_ms !== null && m.first_answer_ms !== undefined && `首字 ${seconds(m.first_answer_ms)}`,
    `用时 ${seconds(m.duration_ms)}`,
    m.tool_calls ? `模型补查 ${m.tool_calls} 次` : '',
    tokens(m.total_tokens, m.calls_without_usage),
    m.model ? `模型 ${m.model}${m.thinking ? '（深度思考）' : ''}` : '没有使用大模型',
    m.degraded ? '模型失败，已降级' : '',
  ]
    .filter(Boolean)
    .join(' · ')
}
