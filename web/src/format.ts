import type { AnalysisStatus, Confidence, IncidentStatus, Severity } from './api/types'

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export const STATUS_LABEL: Record<IncidentStatus, string> = {
  open: '未处理',
  acknowledged: '已确认',
  investigating: '排查中',
  mitigated: '已缓解',
  resolved: '已解决',
  closed: '已关闭',
}

export const STATUS_TAG: Record<IncidentStatus, TagType> = {
  open: 'danger',
  acknowledged: 'warning',
  investigating: 'warning',
  mitigated: 'primary',
  resolved: 'success',
  closed: 'info',
}

export const ACTIVE_STATUSES: readonly IncidentStatus[] = ['open', 'acknowledged', 'investigating', 'mitigated']

/** Where an operator can move an incident; mirrors IncidentStatus.canMoveTo in core-service. */
export const NEXT_STATUSES: Record<IncidentStatus, IncidentStatus[]> = {
  open: ['acknowledged', 'investigating', 'mitigated', 'resolved'],
  acknowledged: ['investigating', 'mitigated', 'resolved'],
  investigating: ['mitigated', 'resolved'],
  mitigated: ['investigating', 'resolved'],
  resolved: ['open', 'closed'],
  closed: [],
}

/** The button that moves an incident to a status. */
export const TRANSITION_LABEL: Record<IncidentStatus, string> = {
  open: '重新打开',
  acknowledged: '确认',
  investigating: '开始排查',
  mitigated: '标记已缓解',
  resolved: '标记已解决',
  closed: '关闭',
}

export const SEVERITY_LABEL: Record<Severity, string> = {
  critical: '紧急',
  high: '高',
  medium: '中',
  low: '低',
  info: '提示',
}

export const SEVERITY_TAG: Record<Severity, TagType> = {
  critical: 'danger',
  high: 'danger',
  medium: 'warning',
  low: 'info',
  info: 'info',
}

export const CONFIDENCE_LABEL: Record<Confidence, string> = { high: '高', medium: '中', low: '低' }

export const CONFIDENCE_TAG: Record<Confidence, TagType> = { high: 'danger', medium: 'warning', low: 'info' }

export const ANALYSIS_STATUS_LABEL: Record<AnalysisStatus, string> = {
  pending: '分析中',
  completed: '已完成',
  failed: '失败',
  timed_out: '超时',
}

export function requesterLabel(requestedBy: string): string {
  return requestedBy === 'system' ? '系统自动' : requestedBy === 'user' ? '人工' : requestedBy
}

const timeFormat = new Intl.DateTimeFormat('zh-CN', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

/** Local time, e.g. "10/17 18:34:57". */
export function formatTime(iso: string | undefined): string {
  return iso ? timeFormat.format(new Date(iso)) : '—'
}

/** "45 秒", "3 分 5 秒", "2 小时 10 分". */
export function formatSeconds(totalSeconds: number): string {
  const s = Math.max(0, Math.round(totalSeconds))
  if (s < 60) {
    return `${s} 秒`
  }
  if (s < 3600) {
    const rest = s % 60
    return rest ? `${Math.floor(s / 60)} 分 ${rest} 秒` : `${s / 60} 分`
  }
  const minutes = Math.floor((s % 3600) / 60)
  return minutes ? `${Math.floor(s / 3600)} 小时 ${minutes} 分` : `${Math.floor(s / 3600)} 小时`
}

export function between(fromIso: string, toIso: string): string {
  return formatSeconds((Date.parse(toIso) - Date.parse(fromIso)) / 1000)
}

/** "刚刚", "5 分钟前", "3 小时前", or the time itself after a day. */
export function ago(iso: string, now: number = Date.now()): string {
  const s = (now - Date.parse(iso)) / 1000
  if (s < 30) {
    return '刚刚'
  }
  if (s < 3600) {
    return `${Math.max(1, Math.round(s / 60))} 分钟前`
  }
  if (s < 86400) {
    return `${Math.floor(s / 3600)} 小时前`
  }
  return formatTime(iso)
}

export function shortId(id: string): string {
  return id.slice(0, 8)
}
