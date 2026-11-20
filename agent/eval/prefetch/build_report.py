"""Render descriptive tables from retained traces and separate Codex evidence reviews. No model calls."""
import json
from collections import Counter
from pathlib import Path

from ops_agent.chat.prefetch_eval import quantiles, summarize

HERE = Path(__file__).parent
REPO = HERE.parents[2]


def read_rows(folder):
    return [json.loads(s) for s in (folder/'rows.jsonl').read_text(encoding='utf8').splitlines()]


def seconds(ms):
    return '缺失' if ms is None else f'{ms/1000:.3f}'


def fraction(n, d):
    return f'{n}/{d} ({n/d:.1%})' if d else '0/0'


def main():
    text = ['# 证据预取开发评测（2026-11-17）', '',
        '本报告区分初始证据齐备、无需额外取证且答对、真实 HTTP/SSE 答案首字延迟。所有数字仅描述本次有限开发评测，不是生产 SLA、独立留出集成绩或 95% 覆盖保证。', '',
        '主批次为 20 个固定问题 × 3 次重复 × on/off 两臂，共 120 次本地靶场真实 Java/PostgreSQL 服务请求；另有 8 个受控问题 × 两臂，共 16 次快照回放请求。共享数据只有 5 个 Incident、33 条告警、2,604 条应用日志，属于靶场数据，非生产业务观测。真实服务与回放结果不合并。', '',
        '运行条件、源 ID、固定时钟、逐题 rubric、复现命令和口径详见 [数据说明](../agent/eval/prefetch/README.md)。客户端为 httpx，服务为本机临时 uvicorn，测量包括 HTTP/TCP/SSE、路由、查询和模型等待，不含浏览器渲染及外部反向代理。当前模型为 gpt-6-luna，temperature=0.1，应用 thinking=false、fast_extra_body={}，最多 4 次工具执行预算；供应商 reasoning 没有额外显式关闭参数。两臂 casework=false、writer=None，均不执行处置写入。', '',
        '数据/评分版本：问题和语义 rubric 在主运行前固定；运行中发现旧日志样本被后续告警复用、以及按整条告警匹配会误收不相关样本。v1.2 修正证据评分器的 Incident 归属和单样本消息匹配，对两臂保存的首次 pack 一致重算，未重新调用模型。原始 v1.1 分数与标签保留。因评分器在观察结果后修正，本批明确属于开发评测。', '',
        '答案由 Codex 对照原始证据及固定 rubric 逐条复核，保存理由与答案哈希；没有盲评或独立人工双评。自动 contracts 只检查路由、引用可解析和有限表达，不代表答案正确。', '']
    summaries, sources = {}, {}
    for name, cohort, title in [('live-v11', 'live_core', '真实服务'), ('controlled-v12', 'controlled', '受控快照回放')]:
        folder = HERE/'results'/name
        rows = read_rows(folder)
        reviews = [json.loads(s) for s in (folder/'reviews.jsonl').read_text(encoding='utf8').splitlines()]
        assert len({r['row_id'] for r in reviews}) == len(rows) == len(reviews)
        summary = summarize(folder, folder/'reviews.jsonl'); summaries[name] = summary; sources[name] = rows
        text += [f'## {title}', '',
            '| 指标 | 预取 on | 预取 off |', '|---|---:|---:|']
        tables = [summary['tables'][f'{cohort}/{arm}/all'] for arm in ('on', 'off')]
        for label, key in [('尝试次数', 'attempts'), ('完整请求完成', 'completed'), ('传输错误/超时', 'transport_failures'),
                           ('未完成（可与传输失败重叠）', 'noncompleted'), ('降级回答', 'degraded'), ('core API 失败调用', 'core_api_failed_calls')]:
            text.append(f'| {label} | {tables[0][key]} | {tables[1][key]} |')
        for label, key in [('初始证据齐备（v1.2）', 'prefetch_sufficient'), ('语义复核正确', 'correct'),
                           ('零额外取证且正确', 'zero_extra_evidence_and_correct'), ('模型提出补充只读取证请求', 'supplemented'),
                           ('路由标签命中（不等于实体/窗口正确）', 'route_correct'), ('自动 contracts 全通过', 'contract_pass')]:
            text.append('| '+label+' | '+' | '.join(fraction(t[key], t['attempts']) for t in tables)+' |')
        text += ['', f"模型提出的只读取证请求总数：on={tables[0]['read_tool_requests_total']}，off={tables[1]['read_tool_requests_total']}；实际执行工具总数：on={tables[0]['executed_tools_total']}，off={tables[1]['executed_tools_total']}。原始 read_tool_calls 是请求清单，可能包含被预算拒绝的请求，不能把它当作执行次数。零额外取证口径要求没有该类请求且没有第二轮答案模型调用。"]
        text += ['', '下表单位为秒；每格列为 `n / 中位数 / P90 / P95 / 最大值`。', '',
                 '| HTTP/SSE 客户端观测 | on | off |', '|---|---|---|']
        for label, key in [('首事件', 'first_event_ms'), ('首个答案正文', 'first_answer_ms'),
                           ('排除澄清暂停后的答案正文', 'nonpaused_first_answer_ms'),
                           ('最后一次 interim 后保留正文首字', 'first_final_answer_ms'), ('完整请求', 'complete_ms')]:
            text.append('| '+label+' | '+' | '.join(str(t[key]['n'])+' / '+' / '.join(seconds(t[key][k]) for k in ('median','p90','p95','max')) for t in tables)+' |')
        text += ['', '分位数只使用已完成且具有该观测值的请求；失败/缺失未填零，尝试分母保留。type 7 线性插值：`h=(n−1)p`。主批每臂 60 次仅 20 个独特问题，P95 约由最高 3 个观测决定，不能作可靠总体尾延迟估计；回放每臂 8 次更不适于估计总体 P95。', '',
                 '| 类别 | on：齐备 / 正确 / 零额外且正确 | off：齐备 / 正确 / 零额外且正确 |', '|---|---|---|']
        for category in sorted({r['category'] for r in rows}):
            cells=[]
            for arm in ('on','off'):
                t=summary['tables'][f'{cohort}/{arm}/{category}']
                cells.append(' / '.join(f"{t[k]}/{t['attempts']}" for k in ('prefetch_sufficient','correct','zero_extra_evidence_and_correct')))
            text.append('| '+category+' | '+' | '.join(cells)+' |')
        text += ['', '阶段耗时是 ledger 中单个 span 的分布，多个并行 API 时长不能直接求和当墙钟。单位秒，格式为 `n / 中位数 / P95`。', '',
                 '| 阶段 span | on | off |', '|---|---|---|']
        stages = sorted(set().union(*(t['stage_spans_ms'] for t in tables)))
        for stage in stages:
            cells=[]
            for t in tables:
                q=t['stage_spans_ms'].get(stage,quantiles([]));cells.append(f"{q['n']} / {seconds(q['median'])} / {seconds(q['p95'])}")
            text.append('| '+stage+' | '+' | '.join(cells)+' |')
        text += ['', '路由方法的成本分开统计，均为本次观测值。规则/模型/模型失败回退不混为“零路由成本”。单位秒。', '',
                 '| 路由方法/臂 | 回答数 | 路由中位数/P95 | 答案首字中位数/P95 |', '|---|---:|---|---|']
        for method in sorted({r['route'].get('method','none') for r in rows}):
            for enabled, arm in [(True,'on'),(False,'off')]:
                selected=[r for r in rows if r['prefetch']==enabled and r['route'].get('method','none')==method]
                q=quantiles([r['route'].get('duration_ms') for r in selected]); a=quantiles([r['http_first_answer_ms'] for r in selected])
                text.append(f"| {method}/{arm} | {len(selected)} | {seconds(q['median'])}/{seconds(q['p95'])} | {seconds(a['median'])}/{seconds(a['p95'])} |")
        text += ['', '缓存、后台报告与路由逐臂统计（未禁用供应商缓存，不能当作冷缓存试验）：', '']
        for arm, enabled in [('on',True),('off',False)]:
            selected=[r for r in rows if r['prefetch']==enabled]
            states=Counter(); reports=0
            for r in selected:
                incident_rows=(r.get('first_pack') or {}).get('incidents',[])
                reports += any(i.get('background_analysis') for i in incident_rows)
                for i in incident_rows:
                    task=i.get('background_task') or {}
                    if task: states[task.get('status','unknown')+('/stale' if task.get('stale') else '')]+=1
            spans=[s for r in selected for s in (r.get('run') or {}).get('usage',{}).get('spans',[]) if s['kind']=='model']
            errors=Counter(s.get('error','unknown') for s in spans if s['status']=='error')
            cache_turns=sum((r['done'].get('cached_tokens') or 0)>0 for r in selected)
            missing_usage=sum(r['done'].get('calls_without_usage') or 0 for r in selected)
            text += [f"- {arm}：路由方法 {dict(Counter(r['route'].get('method','none') for r in selected))}；首次 pack 有可用后台报告 {reports}/{len(selected)}；任务状态（按 Incident 出现次数）{dict(states)}。供应商返回缓存 token 的回答 {cache_turns}/{len(selected)}，缓存 token 总数 {sum(r['done'].get('cached_tokens') or 0 for r in selected)}；缺 usage 的模型调用 {missing_usage}；ledger 模型异常 {dict(errors)}。"]
        text += ['', f'原始记录：[rows.jsonl](../agent/eval/prefetch/results/{name}/rows.jsonl)、[manifest](../agent/eval/prefetch/results/{name}/manifest.json)、[重算分数](../agent/eval/prefetch/results/{name}/scores.json)、[逐条复核](../agent/eval/prefetch/results/{name}/reviews.jsonl)、[全部统计](../agent/eval/prefetch/results/{name}/summary.json)。', '']
        bad = [r for r in reviews if not r['correct']]
        if bad:
            text += ['本组未通过语义复核的全部回答（row_id 末位 1=on、0=off）：', '',
                     '| row_id | 复核理由 |', '|---|---|']
            text += [f"| {r['row_id']} | {r['reason'].replace('|','/')} |" for r in bad]
            text += ['']
    text += ['## 失败、局限与责任边界', '',
        '- 原始证据齐备不能保证答案正确，也不能保证模型不补查。例如 inventory-colloquial 将整个 dependency_call_failed 聚合误写为库存失败数量；status-services 遗漏两个较早 Incident；bounded-alerts 在 off 臂把窗口日志数 19 当作告警 count 3。逐条复核文件列出全部错误，不能用引用可解析或组件关键词命中替代。',
        '- 多日比较可能触发路由超时、模型把两次独立历史事件合并；第四/第五 Incident 只拿到元数据时，缺少所需详情。样本 ID 跨告警复用也要求评分器检查 Incident 归属。当前小数据已能暴露截断/范围问题，尚未证明规模扩大后的齐备率。',
        '- API 查询可因范围超过 24 小时失败；供应商过载可触发降级或超时。这些仍在正式尝试分母及原始 ledger 中，没有筛掉慢样本。降级可能仍给出正确有限事实，也可能未回答比较问题，分别复核。',
        '- 后台任务失败来自本机代理导致 localhost 请求 nginx 404，发生在模型调用前；评测自身对本机 API 绕过代理。没有把后台失败当作模型错误，也没有假装报告已完成。',
        '- source corpus 仅含应用日志；无法用其确认 MySQL 停机具体原因、当前恢复、数据库/消息中间件原生状态。容器状态问句的既有路由限制也未在本任务修复。',
        '- 此消融关闭处置单并排除写操作；prefetch off 也跳过对应图编排。差异不能归结为单次并行请求。随机/交替顺序减少固定次序偏差，但不能消除供应商缓存、网络与服务负载变化。',
        '- pilot 与错误正常时钟导致中止的 live-v1 放在 calibration/，不进入主统计；保留中止原因与原始结果。v1.1 标签、v1.2 证据重算及问题不变的关系由 dataset-manifest 记录。', '',
        '## 简历与面试可用表述', '']
    on=summaries['live-v11']['tables']['live_core/on/all'];off=summaries['live-v11']['tables']['live_core/off/all']
    text += [f"实现告警、样本及服务日志的并行预取，并建立 20 问、120 次真实 HTTP/SSE 对照评测；本次 gpt-6-luna 开发样本的答案首字中位数由 {seconds(off['first_answer_ms']['median'])} s 变为 {seconds(on['first_answer_ms']['median'])} s，预取臂初始证据齐备 {on['prefetch_sufficient']}/60、无需额外取证且答对 {on['zero_extra_evidence_and_correct']}/60。", '',
        f"面试时同时说明：预取臂 P95 为 {seconds(on['first_answer_ms']['p95'])} s，对照臂为 {seconds(off['first_answer_ms']['p95'])} s；这是共享 5 个 Incident 的开发样本、含正常/澄清/不足证据问题及供应商异常，不是生产保证。旧 3.1→1.4 s 属于 DeepSeek/mysql-down、每臂 6 个回答的进程内中位数，与本表模型及测量边界不同。", '',
        '## 验证与清理', '',
        '评测专用回归测试 13 项通过：快照哈希/窗口、过滤、反事实隔离、错误 Incident 与重用样本、服务事件计数、正确澄清、缺失分位数/失败分母、contracts 与正确性分离，以及 SSE 首事件/正文/interim 边界。实际 MySQL 目标的三服务日志计数与最新错误 ID 已与回放核对一致。源数据前后及运行后清理记录见 [provenance](../agent/eval/prefetch/results/provenance/)；22 项核对及 2,604 行日志全部一致。清理前流量/活动故障为零，四个应用健康检查为 UP；清理恢复评测前 9 组应用进程关闭状态，保留健康 Docker 容器，不重建环境。', '']
    (REPO/'docs/prefetch-evaluation.md').write_text('\n'.join(text),encoding='utf8')


if __name__ == '__main__': main()
