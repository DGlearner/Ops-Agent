"""Evaluation regressions: wrong evidence and missing timings must not become successes."""
import copy
import json
from pathlib import Path
from datetime import datetime, timedelta

import httpx
import pytest

from ops_agent.chat.prefetch_data import SnapshotCore, digest, scenario
from ops_agent.chat.prefetch_eval import contract_checks, evidence_checks, load_cases, quantiles, summarize

ROOT = Path(__file__).resolve().parents[1] / 'eval/prefetch'


@pytest.fixture
def corpus():
    return json.loads((ROOT / 'corpus.json').read_text(encoding='utf8'))


def test_capture_hash_and_bound_labels(corpus):
    unsigned = {k: v for k, v in corpus.items() if k != 'sha256'}
    assert digest(unsigned) == corpus['sha256']
    cases = load_cases(ROOT / 'cases.jsonl')
    ids = {i['incident_id'] for i in corpus['incidents']}
    for c in cases:
        assert set(c['targets']) <= ids
        if c['category'] == 'normal':
            assert c['window']['to'] == c['now']
            # Product queries intentionally add one minute at the upper bound.
            start = datetime.fromisoformat(c['window']['from'])
            end = datetime.fromisoformat(c['now']) + timedelta(minutes=1)
            assert not [a for aa in corpus['alerts'].values() for a in aa
                        if start <= datetime.fromisoformat(a['created_at'].replace('Z', '+00:00')) < end]


def test_snapshot_filters_and_real_timestamps(corpus):
    snapshot = SnapshotCore(corpus)
    with httpx.Client(transport=httpx.MockTransport(snapshot.handle), base_url='http://snapshot') as client:
        row = corpus['logs'][0]
        params = {'from': row['timestamp'], 'to': corpus['to'], 'service': row['service'],
                  'event_type': row['event_type'], 'limit': 200}
        logs = client.get('/api/v1/logs', params=params).json()['items']
        assert logs and all(r['service'] == row['service'] and r['event_type'] == row['event_type'] for r in logs)
        assert client.post('/api/v1/logs').status_code == 405
        params['from'] = params['to']
        assert client.get('/api/v1/logs', params=params).json()['items'] == []


@pytest.mark.parametrize('mutation', ['pending', 'stale', 'failed', 'missing'])
def test_controlled_mutation_isolated(corpus, mutation):
    before = copy.deepcopy(corpus)
    target = load_cases(ROOT / 'cases.jsonl')[2]['targets'][0]
    changed = scenario(corpus, mutation, target)
    assert corpus == before
    if mutation == 'missing':
        assert not changed['logs'] and not changed['analyses'][target]
        assert all(not a['samples'] for a in changed['alerts'][target])
    else:
        a = changed['analyses'][target][0]
        assert a['status'] == ('completed' if mutation == 'stale' else mutation)


def test_wrong_incident_or_context_does_not_pass():
    case = {'targets': ['correct'], 'required': [
        {'id': 'source', 'ids': ['correct-event']},
        {'id': 'context', 'kind': 'service_context', 'service': 'payment', 'event_counts': {'paid': 4}}]}
    refs = {'I1': {'id': 'wrong', 'kind': 'incident'}, 'E1': {'id': 'wrong-event', 'kind': 'log'}}
    pack = {'incidents': [{'ref': 'I1', 'samples': [{'ref': 'E1'}],
                           'service_logs': {'payment': {'events': [{'event_type': 'paid', 'count': 4}],
                                                        'warnings_and_errors': []}}}]}
    assert not any(c['ok'] for c in evidence_checks(case, pack, refs, []))
    refs['I1']['id'] = 'correct'; refs['E1']['id'] = 'correct-event'
    assert all(c['ok'] for c in evidence_checks(case, pack, refs, []))
    pack['incidents'][0]['service_logs']['payment']['events'][0]['count'] = 400
    assert not evidence_checks(case, pack, refs, [])[1]['ok']


def test_questionnaire_counts_as_clarification_without_model():
    case = {'targets': ['a', 'b'], 'required': [{'id': 'clarify', 'kind': 'clarify', 'ids': ['a', 'b']}],
            'behavior': 'clarify', 'routes': ['ops_diagnosis']}
    refs = {k: {'id': v, 'kind': 'incident'} for k, v in [('I1', 'a'), ('I2', 'b')]}
    events = [('questionnaire', {}), ('route', {'route': 'ops_diagnosis'})]
    assert evidence_checks(case, None, refs, events)[0]['ok']
    assert all(contract_checks(case, '', [], events, {'status': 'completed'}, refs).values())
    assert not evidence_checks(case, None, refs, [])[0]['ok']


def test_reused_sample_in_other_incident_not_sufficient():
    c = {'required': [{'id': 'cause', 'ids': ['shared-event'], 'incident_id': 'old'}]}
    refs = {'I1': {'id': 'old'}, 'I2': {'id': 'new'}, 'E1': {'id': 'shared-event'}}
    p = {'incidents': [{'ref': 'I1'}, {'ref': 'I2', 'samples': [{'ref': 'E1'}]}]}
    assert not evidence_checks(c, p, refs, [])[0]['ok']
    p['incidents'][0]['samples'] = [{'ref': 'E1'}]
    assert evidence_checks(c, p, refs, [])[0]['ok']


def test_absence_requires_query_scope():
    c = {'required': [{'id': 'empty', 'kind': 'absence'}]}
    assert not evidence_checks(c, {}, {}, [])[0]['ok']
    assert evidence_checks(c, {'scope': {'window': '5 minutes'}, 'recent_alerts': [], 'incidents': []}, {}, [])[0]['ok']


def test_quantiles_keep_missing_separate():
    assert quantiles([None, 1, 2, 3, 4, 5]) == {'n': 5, 'median': 3, 'p90': 4.6, 'p95': 4.8, 'max': 5}
    assert quantiles([None])['n'] == 0


def test_failure_denominator_and_contract_not_correctness(tmp_path):
    common = {'cohort': 'live_core', 'prefetch': True, 'category': 'test', 'case_id': 'a',
              'prefetch_sufficient': True, 'contracts': {'route': True}, 'route': {},
              'read_tool_calls': [], 'extra_answer_model_calls': 0, 'core_calls': [], 'first_pack': {},
              'http_first_event_ms': 1, 'http_first_answer_ms': 10, 'http_first_final_answer_ms': 10,
              'wall_ms': 20, 'run': None}
    rows = [{**common, 'row_id': 'ok', 'transport_status': 'ok', 'done': {'status': 'completed'}},
            {**common, 'row_id': 'bad', 'transport_status': 'timeout', 'done': {}, 'http_first_answer_ms': None}]
    (tmp_path / 'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    s = summarize(tmp_path)['tables']['live_core/on/all']
    assert s['attempts'] == 2 and s['completed'] == 1 and s['transport_failures'] == 1
    assert s['first_answer_ms']['n'] == 1
    assert s['contract_pass'] == 2 and s['correct'] == 0 and s['semantic_reviewed'] == 0


@pytest.mark.asyncio
async def test_sse_first_event_is_not_first_answer_and_interim_resets_final(monkeypatch):
    from types import SimpleNamespace
    from ops_agent.chat import prefetch_eval as module
    events = [('meta', {}), ('step', {}), ('answer', {'delta': 'initial'}),
              ('interim', {}), ('answer', {'delta': ' '}), ('answer', {'delta': 'final'}),
              ('done', {'status': 'completed'})]
    body = ''.join('event: '+e+'\ndata: '+json.dumps(d)+'\n\n' for e, d in events)
    clock = iter(range(100))
    monkeypatch.setattr(module.time, 'perf_counter', lambda: next(clock) / 1000)
    case = {'id': 'timing', 'question': 'test', 'cohort': 'controlled', 'category': 'test',
            'targets': [], 'routes': [], 'behavior': 'answer',
            'required': [{'id': 'none', 'ids': ['absent']}]}
    store = SimpleNamespace(session=lambda _: {'refs': {}})
    core = SimpleNamespace(calls=[], phase='prefetch')
    model = SimpleNamespace(calls=[])
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, text=body)),
                                 base_url='http://eval') as client:
        r = await module._http_turn(client, 's', case, None, store, core, model, 5)
    assert r['http_first_event_ms'] < r['http_first_answer_ms'] < r['http_first_final_answer_ms']
    assert r['http_first_event_ms'] == r['event_at_ms'][0]
    assert r['http_first_answer_ms'] == r['event_at_ms'][2]
    assert r['http_first_final_answer_ms'] == r['event_at_ms'][5]
    assert r['answer'] == ' final'
