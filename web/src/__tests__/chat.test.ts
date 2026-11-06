import { describe, expect, it } from 'vitest'
import { type ActionCard, SseParser, type UsageSpan } from '@/api/chat'
import {
  applyEvent,
  canDecide,
  citationLink,
  currentCard,
  filledAnswers,
  metricsLine,
  newAnswer,
  renderAnswer,
  replyText,
  spanBar,
  tokens,
} from '@/chat'

describe('SseParser', () => {
  it('reads events split anywhere by the network, and skips the keep-alive comments', () => {
    const parser = new SseParser()
    const stream = 'event: route\r\ndata: {"route":"ops_query"}\r\n\r\n: ping\r\n\r\nevent: answer\ndata: {"delta":"你好"}\n\n'
    const events = []
    for (let i = 0; i < stream.length; i += 7) {
      events.push(...parser.feed(stream.slice(i, i + 7)))
    }

    expect(events).toEqual([
      { event: 'route', data: '{"route":"ops_query"}' },
      { event: 'answer', data: '{"delta":"你好"}' },
    ])
  })

  it('joins data lines and keeps an unfinished event for later', () => {
    const parser = new SseParser()

    expect(parser.feed('data: a\ndata: b\n\nevent: x\ndata: c')).toEqual([{ event: 'message', data: 'a\nb' }])
    expect(parser.feed('\n\n')).toEqual([{ event: 'x', data: 'c' }])
  })
})

describe('applyEvent', () => {
  it('puts the stream together as the server keeps it', () => {
    const m = newAnswer()
    const events: [string, Record<string, unknown>][] = [
      ['meta', { message_id: 'm1' }],
      ['route', { route: 'ops_diagnosis', label: '故障诊断', method: 'rule', reason: 'r', duration_ms: 0 }],
      ['step', { id: 's1', kind: 'locate', title: '定位', status: 'running' }],
      ['step', { id: 's1', kind: 'locate', title: '定位', status: 'done', result: '1 个', duration_ms: 3 }],
      ['thinking', { delta: '先看' }],
      ['thinking', { delta: '规则' }],
      ['answer', { delta: '我查一下' }],
      ['interim', { text: '我查一下' }],
      ['thinking', { delta: '够了' }],
      ['answer', { delta: '结论 [I1]' }],
      ['citations', { items: [{ ref: 'I1', verified: true, kind: 'incident', id: 'X' }] }],
      ['done', { message_id: 'm1', status: 'completed', route: 'ops_diagnosis', duration_ms: 900, tool_calls: 0 }],
    ]
    for (const [e, d] of events) {
      applyEvent(m, e, d)
    }

    expect(m.message_id).toBe('m1')
    expect(m.content).toBe('结论 [I1]')
    expect(m.status).toBe('completed')
    expect(m.trace!.items).toEqual([
      { type: 'step', id: 's1', kind: 'locate', title: '定位', status: 'done', result: '1 个', duration_ms: 3 },
      { type: 'thought', text: '先看规则' },
      { type: 'thought', text: '我查一下', interim: true },
      { type: 'thought', text: '够了' },
    ])
    expect(m.trace!.metrics).toEqual({ duration_ms: 900, tool_calls: 0 })
  })
})

describe('renderAnswer', () => {
  const cites = [
    { ref: 'I1', verified: true, kind: 'incident' as const, id: 'inc-1', label: 'MySQL 不可用' },
    { ref: 'A2', verified: true, kind: 'alert' as const, id: 'al-2', label: '连接失败', incident_id: 'inc-1' },
    { ref: 'A9', verified: false },
  ]

  it('links the refs it can and marks the ones that are not in the data', () => {
    const html = renderAnswer('**结论**：MySQL 挂了 [I1]，见 [A2, A9]。', cites)

    expect(html).toContain('<strong>结论</strong>')
    expect(html).toContain('<a class="cite" href="/incidents/inc-1" data-route="/incidents/inc-1"')
    expect(html).toContain('href="/incidents/inc-1?alert=al-2"')
    expect(html).toContain('<span class="cite unverified"')
  })

  it('does not let the model inject markup or script links', () => {
    const html = renderAnswer('<img src=x onerror=alert(1)> [点我](javascript:alert(1)) [I1]', cites)

    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
    expect(html).not.toContain('href="javascript:')
  })

  it('leaves refs plain while the citations are not checked yet', () => {
    expect(renderAnswer('见 [A1]', [])).toContain('<span class="cite pending">A1</span>')
  })

  it('leaves ordinary brackets and links alone', () => {
    const html = renderAnswer('数组 [1, 2] 和 [文档](https://example.com)', [])

    expect(html).toContain('[1, 2]')
    expect(html).toContain('<a href="https://example.com">文档</a>')
  })
})

describe('citationLink', () => {
  it('has nowhere to go for an unverified ref or a sample without its incident', () => {
    expect(citationLink({ ref: 'A9', verified: false })).toBeUndefined()
    expect(citationLink({ ref: 'E1', verified: true, kind: 'event', id: 'e', incident_id: null })).toBeUndefined()
    expect(citationLink({ ref: 'E1', verified: true, kind: 'event', id: 'e', incident_id: 'i' })).toBe('/incidents/i')
  })
})

describe('metricsLine', () => {
  it('says how the answer came about', () => {
    const line = metricsLine(
      { duration_ms: 8400, first_answer_ms: 1200, tool_calls: 1, degraded: false, model: 'deepseek-v4-pro' },
      {
        route: 'ops_diagnosis',
        label: '故障诊断',
        method: 'model',
        reason: '',
        duration_ms: 950,
        entities: { services: [], incident_ids: [], follow_up: false },
      },
    )

    expect(line).toBe('故障诊断（模型判断，950 ms） · 首字 1.2 秒 · 用时 8.4 秒 · 模型补查 1 次 · 模型 deepseek-v4-pro')
    expect(metricsLine({ duration_ms: 1, tool_calls: 0, degraded: false, model: 'm', thinking: true }, null)).toBe(
      '用时 1 ms · 模型 m（深度思考）',
    )
  })
})

describe('tokens', () => {
  it('does not pass off a lower bound as the total', () => {
    expect(tokens(1234, 0)).toBe('1,234 tokens')
    expect(tokens(1234, 2)).toBe('至少 1,234 tokens')
    expect(tokens(null, 2)).toBe('用量未知')
    expect(tokens(null, 0)).toBe('')
    expect(tokens(undefined, undefined)).toBe('')
  })

  it('is in the line under the answer', () => {
    const m = { duration_ms: 1, tool_calls: 0, degraded: false, model: 'm', total_tokens: 12, calls_without_usage: 0 }
    expect(metricsLine(m, null)).toBe('用时 1 ms · 12 tokens · 模型 m')
  })
})

describe('spanBar', () => {
  const span = (at_ms: number, duration_ms: number | null): UsageSpan => ({
    kind: 'model',
    name: 'm',
    at_ms,
    duration_ms,
    status: 'ok',
  })

  it('places a span on the run', () => {
    expect(spanBar(span(250, 500), 1000)).toEqual({ left: '25.00%', width: '50.00%' })
  })

  it('keeps a short span visible and inside the run', () => {
    expect(spanBar(span(0, 0), 1000).width).toBe('0.50%')
    expect(spanBar(span(999, null), 1000)).toEqual({ left: '99.90%', width: '0.10%' })
    expect(spanBar(span(0, 0), 0)).toEqual({ left: '0.00%', width: '0.50%' })
  })
})

describe('cards', () => {
  const card: ActionCard = {
    action_id: 'a1',
    session_id: 's',
    message_id: 'm',
    kind: 'transition_incident',
    status: 'proposed',
    version: 1,
    title: '把 I1 改为「已确认」',
    params: { to_status: 'acknowledged' },
    risk: 'medium',
    reason: 'r',
    target: { ref: 'I1', incident_id: 'i', title: 't', status: 'open', version: 1 },
    evidence: [],
    receipt: null,
    created_at: '2026-11-01T10:00:00Z',
    updated_at: '2026-11-01T10:00:00Z',
    expires_at: '2026-11-01T10:15:00Z',
  }

  it('keeps the cards and the questionnaire an answer streams', () => {
    const m = newAnswer()
    applyEvent(m, 'action', { ...card })
    applyEvent(m, 'memory', { memory_id: 'k1', text: '经验', status: 'candidate' })
    applyEvent(m, 'questionnaire', { pause_id: 'p1', questions: [] })

    expect(m.trace?.actions?.map((a) => a.action_id)).toEqual(['a1'])
    expect(m.trace?.memories?.[0]?.memory_id).toBe('k1')
    expect(m.trace?.questionnaire?.pause_id).toBe('p1')
  })

  it('offers the buttons only on a proposed card that has not expired', () => {
    const before = Date.parse('2026-11-01T10:05:00Z')
    const after = Date.parse('2026-11-01T10:20:00Z')

    expect(canDecide({ ...card }, before)).toBe(true)
    expect(canDecide({ ...card }, after)).toBe(false)
    expect(canDecide({ ...card, status: 'succeeded' }, before)).toBe(false)
  })

  it('shows a card as it stands now when the session knows it', () => {
    const done = { ...card, status: 'succeeded' as const, version: 3 }

    expect(currentCard({ ...card }, { a1: done }).status).toBe('succeeded')
    expect(currentCard({ ...card }, {}).status).toBe('proposed')
  })

  it('words the answers as the server does, and leaves out the empty ones', () => {
    const q = {
      pause_id: 'p',
      source: 'orchestration' as const,
      expires_at: '',
      questions: [
        { id: 'target', title: '你问的是哪个？', kind: 'single' as const, allow_text: false,
          options: [{ id: 'I2', label: '[I2] payment 503' }] },
        { id: 'seen', title: '看到了什么？', kind: 'single' as const, allow_text: true, options: [] },
      ],
    }
    const answers = filledAnswers([
      { id: 'target', choices: ['I2'], text: '' },
      { id: 'seen', choices: [], text: '  ' },
    ])

    expect(answers).toEqual([{ id: 'target', choices: ['I2'] }])
    expect(replyText(q, answers)).toBe('你问的是哪个？ [I2] payment 503')
  })
})
