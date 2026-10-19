import { describe, expect, it } from 'vitest'
import { SseParser } from '@/api/chat'
import { applyEvent, citationLink, metricsLine, newAnswer, renderAnswer } from '@/chat'

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
  })
})
