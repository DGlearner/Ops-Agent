// What the chat page does with the stream: each event updates the answer being shown, the same way the server puts
// the events together when it keeps the answer (agent/src/ops_agent/chat/agent.py, Recorder), so a reloaded page
// looks like the live one. And the answer's Markdown, with its refs ([A1]) turned into links.

import MarkdownIt from 'markdown-it'
import type { ChatMessage, Citation, Metrics, RouteInfo, StepItem } from './api/chat'

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
  return c.kind === 'alert' ? `/incidents/${c.incident_id}?alert=${c.id}` : `/incidents/${c.incident_id}`
}

const KIND_LABEL: Record<NonNullable<Citation['kind']>, string> = { incident: 'Incident', alert: '告警', event: '日志' }

export function citationTitle(c: Citation): string {
  if (!c.verified) {
    return `${c.ref}：不在查到的数据里，可能是模型写错了`
  }
  return `${c.ref} · ${c.kind ? KIND_LABEL[c.kind] : ''}：${c.label ?? ''}`
}

const CITATION = /^\[\s*([AIE]\d{1,4}(?:\s*[,，、]\s*[AIE]\d{1,4})*)\s*\]/

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
      const refs = match[1]!.match(/[AIE]\d{1,4}/g) ?? []
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

export function seconds(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) {
    return '—'
  }
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} 秒`
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
    m.model ? `模型 ${m.model}${m.thinking ? '（深度思考）' : ''}` : '没有使用大模型',
    m.degraded ? '模型失败，已降级' : '',
  ]
    .filter(Boolean)
    .join(' · ')
}
