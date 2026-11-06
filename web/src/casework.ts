// Labels for cases (docs/agent-design.md §2, §6) and the review draft put together from one.
import type { Case, CaseEntry, Remediation, Verification } from '@/api/types'

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const CASE_STATUS: Record<Case['status'], { label: string; type: TagType }> = {
  open: { label: '新建', type: 'info' },
  diagnosing: { label: '诊断中', type: 'warning' },
  mitigating: { label: '处置中', type: 'warning' },
  verifying: { label: '验证中', type: 'primary' },
  monitoring: { label: '观察中', type: 'success' },
  resolved: { label: '已验收', type: 'success' },
  handed_over: { label: '已交接', type: 'info' },
}

export const ENTRY_KIND: Record<CaseEntry['kind'], string> = {
  statement: '用户说',
  observation: '观察',
  fact: '事实',
  hypothesis: '假设',
  constraint: '限制',
  decision: '决定',
  note: '备注',
}

export const ENTRY_SOURCE: Record<CaseEntry['source_type'], string> = {
  user: '来自用户',
  observation: '探针观察',
  rule: '规则',
  analyst: '后台分析',
  model: '模型推断',
  receipt: '回执',
  verification: '验证',
  system: '系统',
}

export const OUTCOME: Record<NonNullable<Verification['outcome']>, { label: string; type: TagType }> = {
  recovered: { label: '已恢复', type: 'success' },
  not_recovered: { label: '未恢复', type: 'danger' },
  inconclusive: { label: '证据不足', type: 'warning' },
}

export const VERDICT: Record<'met' | 'not_met' | 'insufficient', string> = {
  met: '✓',
  not_met: '✗',
  insufficient: '？',
}

/** A first draft of the review from what the case recorded; a person edits it, nothing is concluded here. */
export function reviewDraft(c: Case, remediations: Remediation[], verifications: Verification[]): string {
  const current = c.entries.filter((e) => e.status !== 'superseded')
  const lines = [`# 复盘草稿：${c.title}`, '', `状态：${CASE_STATUS[c.status].label}，开始于 ${c.created_at}`, '']
  const section = (title: string, kinds: CaseEntry['kind'][]) => {
    const items = current.filter((e) => kinds.includes(e.kind))
    if (items.length) {
      lines.push(`## ${title}`, ...items.map((e) => `- ${e.text}（${ENTRY_SOURCE[e.source_type]}${e.kind === 'hypothesis' ? `，${e.status}` : ''}）`), '')
    }
  }
  section('观察到的事实', ['fact', 'observation'])
  section('根因假设', ['hypothesis'])
  section('用户提供的情况与限制', ['statement', 'constraint', 'decision'])
  if (remediations.length) {
    lines.push('## 处置与验证')
    for (const r of remediations) {
      lines.push(`- ${r.op} ${JSON.stringify(r.params)}：${r.status}，${r.receipt.summary ?? ''}`)
      for (const v of verifications.filter((x) => x.remediation_id === r.remediation_id && x.status === 'done')) {
        lines.push(`  - 验证：${v.outcome ? OUTCOME[v.outcome].label : '—'}`)
        for (const check of v.result?.checks ?? []) {
          lines.push(`    - ${VERDICT[check.verdict]} ${check.title}：${check.reason}`)
        }
      }
    }
    lines.push('')
  }
  lines.push('## 待补充', '- 影响范围与时长', '- 改进项（监控、Runbook、代码）')
  return lines.join('\n')
}
