"""GET-only source drift check against the captured absolute window; never restores or writes core data."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

import httpx
from ops_agent.chat.prefetch_data import digest, write


def verify(corpus, url):
    checks=[]
    with httpx.Client(base_url=url, trust_env=False, timeout=30) as client:
        def get(path, params=None):
            r=client.get(path,params=params);r.raise_for_status();return r.json()
        def check(name, expected, actual):
            checks.append({'name':name,'match':digest(expected)==digest(actual),
                           'expected_sha256':digest(expected),'actual_sha256':digest(actual)})
        check('topology',corpus['topology'],get('/api/v1/topology'))
        for i in corpus['incidents']:
            key=i['incident_id'];base='/api/v1/incidents/'+key
            check(key+'/incident',i,get(base))
            for field,suffix in [('alerts','alerts'),('analyses','analyses'),('timelines','timeline')]:
                check(key+'/'+field,corpus[field][key],get(base+'/'+suffix))
        logs=[];cursor=None
        while True:
            params={'from':corpus['from'],'to':corpus['to'],'limit':200}
            if cursor:params['cursor']=cursor
            page=get('/api/v1/logs',params);logs.extend(page['items']);cursor=page.get('next_cursor')
            if not cursor:break
            if len(logs)>100000:raise ValueError('source check exceeded bound')
        check('all-log-pages',corpus['logs'],logs)
    return {'checked_at':datetime.now(timezone.utc).isoformat(),'checks':checks,
            'log_count':len(logs),'all_match':all(c['match'] for c in checks)}


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--corpus',type=Path,default=Path(__file__).parent/'corpus.json')
    p.add_argument('--url',default='http://127.0.0.1:8080');p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();result=verify(json.loads(a.corpus.read_text(encoding='utf8')),a.url)
    write(a.out,result);print('source checks',len(result['checks']),'log rows',result['log_count'],'all_match',result['all_match'])
    if not result['all_match']:raise SystemExit(1)
