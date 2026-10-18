import type { Analysis, TimelineEntry } from './api/types'
import { requesterLabel, STATUS_LABEL, type TagType } from './format'

export interface TimelineItem {
  key: string
  at: string
  title: string
  detail?: string
  type: TagType
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value ? value : undefined
}

/** The incident's history and the agent's analyses of it, oldest first. */
export function buildTimeline(entries: TimelineEntry[], analyses: Analysis[]): TimelineItem[] {
  const items: TimelineItem[] = entries.map((e) => {
    switch (e.type) {
      case 'OPENED':
        return { key: e.event_id, at: e.at, title: 'Incident 创建', detail: text(e.payload.title), type: 'danger' }
      case 'ALERT_ATTACHED':
        return {
          key: e.event_id,
          at: e.at,
          title: `告警 · ${text(e.payload.service) ?? text(e.payload.subject) ?? '未知服务'}`,
          detail: text(e.payload.summary),
          type: 'warning',
        }
      case 'STATUS_CHANGED': {
        const from = e.from_status ? STATUS_LABEL[e.from_status] : '?'
        const to = e.to_status ? STATUS_LABEL[e.to_status] : '?'
        return {
          key: e.event_id,
          at: e.at,
          title: `${e.actor_type === 'USER' ? '值班人员' : '系统'}把状态从「${from}」改为「${to}」`,
          detail: text(e.payload.note),
          type: e.to_status === 'resolved' || e.to_status === 'closed' ? 'success' : 'primary',
        }
      }
    }
  })
  for (const a of analyses) {
    items.push({
      key: `${a.analysis_id}-requested`,
      at: a.requested_at,
      title: `请求 Agent 分析（${requesterLabel(a.requested_by)}）`,
      type: 'info',
    })
    if (a.completed_at && a.status === 'completed') {
      const top = a.hypotheses?.[0]
      items.push({
        key: `${a.analysis_id}-completed`,
        at: a.completed_at,
        title: 'Agent 完成分析',
        detail: top ? `最可能的原因：${top.component}${a.uncertain ? '（证据不足，不确定）' : ''}` : undefined,
        type: 'primary',
      })
    } else if (a.completed_at && a.status === 'failed') {
      items.push({ key: `${a.analysis_id}-failed`, at: a.completed_at, title: 'Agent 分析失败', detail: a.error, type: 'info' })
    }
  }
  return items.sort((x, y) => Date.parse(x.at) - Date.parse(y.at))
}
