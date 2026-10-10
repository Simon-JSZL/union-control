"""Rebuild the fixed analysis sample; preserve the original July contract cases."""
import copy
from datetime import date, timedelta
import json
from pathlib import Path

path = Path(__file__).resolve().parents[2] / 'src/main/resources/mock/operations-v1.json'
f = json.loads(path.read_text())
api_template = copy.deepcopy(f['indicators']['t_indicator_accessed_api_info']['items'][0])
PREFIX = 'mock:analysis:'
UPDATED = '2026-10-05T09:00:00+08:00'
for records in [f['running'], f['changes'], f['faults'], *(d['items'] for d in f['indicators'].values())]:
    records[:] = [r for r in records if not r['source_ref'].startswith(PREFIX)]
f['data_updated_at'] = UPDATED
f['notice'] = '固定合成分析样本，非生产事实；完整样本窗口为2026-09-01至2026-10-04、五家机构；另保留七月历史案例。毫秒为本mock约定，不确认生产耗时单位。'
ind = f['indicators']
base = ind['t_indicator_base_info']
for code, name, alias in [('104100000004', '中国银行', '中行'), ('102100099996', '中国工商银行', '工行'), ('103100000026', '中国农业银行', '农行')]:
    r = copy.deepcopy(base['items'][0])
    r.update(source_ref=PREFIX+code, available_at=UPDATED)
    r['dimensions']['org_code'] = code
    r['values'].update(org_code=code, org_name=name, org_category='国有大型商业银行', bank_scale_type='MOCK_LARGE', org_addr_province='北京', org_addr_city='北京', parent_org_code=None)
    base['items'].append(r)
    base['aliases'][code] = [alias, name, {'104100000004':'BOC','102100099996':'ICBC','103100000026':'ABC'}[code]]
    if code == '104100000004': base['aliases'][code].append('中国银行股份有限公司')
orgs = [(r['values']['org_code'], r['values']['org_name']) for r in base['items']]

def record(template, key):
    r = copy.deepcopy(template)
    r.update(source_ref=PREFIX+key, available_at=UPDATED)
    return r

# Explicit synthetic events, including successful controls and rollback faults.
events = [('2026-09-16', 'C_MOCK_B', 0), ('2026-09-22', 'C_MOCK_A', 8),
          ('2026-09-24', '104100000004', 0), ('2026-09-26', '102100099996', 12),
          ('2026-09-29', 'C_MOCK_B', 0), ('2026-10-02', 'C_MOCK_A', 5)]
for day, code, minutes in events:
    name = dict(orgs)[code]
    key = day.replace('-', '')+'-'+code
    change_id, fault_id = 'MOCK-CH-'+key, 'MOCK-FAULT-'+key
    start, end = day+'T02:00:00+08:00', day+'T02:30:00+08:00'
    c = record(f['changes'][0], change_id)
    c.update(change_id=change_id, org_code=code, planned_start=start, planned_end=end,
             related_fault_ids=[fault_id] if minutes else [])
    d = c['details']
    created = (date.fromisoformat(day)-timedelta(days=2)).isoformat()+'T09:00:00+08:00'
    d.update(created_at=created, updated_at=end, actual_start=start, actual_end=end,
             audited_at=created, is_urgent=False, execution_rating='良' if minutes else '优',
             memo='【合成】支付前置灰度发布，预设回退条件及业务验证',
             impact_description='连接池参数变更导致短时超时' if minutes else '灰度验证通过，无业务中断',
             execution_description='【合成】回退配置后交易探测恢复' if minutes else '【合成】灰度发布及回退验证通过')
    d['rule_assessment']['evaluated_at'] = created
    d['business_assessments']['execute'] = d['execution_rating']
    raw = d['source_fields']
    raw.update(id=change_id, seriesNo=change_id, orgCode=code, orgName=name, memberName=name,
               upOrgCode=code, upOrgName=name, createdAt=created[:19].replace('T',' '),
               updatedAt=end[:19].replace('T',' '), executeTime=end[:19].replace('T',' '),
               plannedStartTime=start[:19].replace('T',' '), plannedEndTime=end[:19].replace('T',' '),
               auditTime=created[:19].replace('T',' '), isUrgent='0', changeOffTime=minutes*60, memo=d['memo'])
    raw['execution'].update(seriesNo=change_id, orgCode=code, orgName=name,
                           plannedStartTime=raw['plannedStartTime'], plannedEndTime=raw['plannedEndTime'],
                           execute=d['execution_rating'], memo=d['memo'],
                           executeDesc=json.dumps([{'faultNo':fault_id,'faultCause':'1.生产变更','faultLevel':'轻微'}],ensure_ascii=False) if minutes else None)
    c['unavailable_fields'] = []
    f['changes'].append(c)
    if not minutes:
        continue
    fault = record(f['faults'][0], fault_id)
    occurred = day+'T02:05:00+08:00'
    recovered = day+f'T02:{5+minutes:02d}:00+08:00'
    fault.update(fault_id=fault_id, org_code=code, occurred_at=occurred, recovered_at=recovered,
                 cause_status='confirmed', related_change_ids=[change_id], unavailable_fields=[])
    fault['details'].update(discovered_at=occurred, updated_at=end, business_types=['baseBiz'], status='已恢复',
        phenomenon='【合成】支付前置连接池等待超时',
        impact={'start':occurred,'end':recovered,'duration_minutes':minutes,'failed_transactions':minutes*40,'description':'部分支付请求超时'},
        cause_category='1.生产变更', cause='【合成场景设定】灰度配置缩小连接池，峰值流量下连接等待超时',
        timeline=[{'at':occurred,'event':'监控告警，启动回退'},{'at':recovered,'event':'配置回退完成，探测恢复'}],
        handling='回退连接池参数，核对积压请求处理结果',
        review={'summary':'【合成】预发布压测未覆盖峰值并发'},
        remediation=[{'action':'增加峰值并发回归及连接池阈值监控','status':'完成'}],
        verification=[{'at':end,'result':'连续探测及账务核对通过'}],
        source_fields={'faultNo':fault_id,'faultCause':'1.生产变更','faultLevel':'轻微'})
    f['faults'].append(fault)

start, end = date(2026,9,1), date(2026,10,4)
for i, (code, name) in enumerate(orgs):
    for offset in range((end-start).days+1):
        day = start+timedelta(days=offset)
        iso, ymd = day.isoformat(), day.strftime('%Y%m%d')
        minutes = next((m for d,c,m in events if d==iso and c==code), 0)
        changed = any(d==iso and c==code for d,c,_ in events)
        # Date and institution keyed, with weekday demand and repeatable daily noise.
        total = (180000+i*170000) * (72 if day.weekday()>=5 else 100)//100 + ((day.toordinal()*7919+i*3571)%17003)
        failures = 8+i*3+(day.toordinal()+i*7)%13+minutes*40
        avg = 32+i*4+(day.toordinal()*13+i*11)%19/10+minutes*1.7
        running = {'running_cnt.total_cnt':total,'running_cnt.total_cnt_fail':failures,
                   'running_cnt.total_time_cost':round(total*avg,2),'running_cnt.bank_code':code,
                   'running_cnt.biz_type':'day','running_cnt.ymd':ymd,'mock_data':True,'source_ref':PREFIX+'running:'+code+':'+ymd}
        f['running'].append(running)
        for table in ['change_info','change_info_all_biz','fault_info','sys_code']:
            dataset = ind['t_indicator_'+table]
            r = record(dataset['items'][0], table+':'+code+':'+ymd)
            v = r['values']
            for k, value in v.items():
                if isinstance(value, (float,int)):
                    v[k] = 0
            v.update(org_code=code, query_date=ymd)
            if 'org_name' in v: v['org_name']=name
            r['dimensions']={'org_code':code,'query_date':iso}
            if table.startswith('change_info'):
                v['total_num']=int(changed)
                if table=='change_info':
                    v.update(change_off_time=minutes*60, good_info=int(minutes>0), excellent_info=int(changed and not minutes))
            elif table=='fault_info':
                v.update(impact_time=minutes, minor_fault=int(minutes>0))
            else:
                v.update(sys_code='MOCK_TIMEOUT',sys_code_cnt=failures,sys_code_description='合成超时返回码；本样本失败均为超时')
                r['dimensions'].update(sys_code='MOCK_TIMEOUT',ymd=ymd)
            dataset['items'].append(r)
        api=ind['t_indicator_accessed_api_info']
        r=record(api_template, 'api:'+code+':'+ymd)
        r['values'].update(org_code=code,org_name=name)
        for k,v in r['values'].items():
            if v is None: r['values'][k]='2026-08-01 09:00:00'
        r.update(dimensions={'org_code':code,'snapshot_date':iso,'ymd':ymd},unavailable_fields=[])
        api['items'].append(r)
    cap=ind['t_indicator_operation_security_cap']
    existing=next((r for r in cap['items'] if r['values']['org_code']==code),None)
    if existing is None:
        existing=record(cap['items'][0], 'cap:'+code)
        existing['values'].update(org_code=code,org_name=name,org_category='国有大型商业银行')
        existing['dimensions']['org_code']=code
        cap['items'].append(existing)
    existing['values'].update(cut_cap_score=80+i*3,top_cut_cap_score=100,security_cap_score=82+i*2,top_security_cap_score=100,
                              cut_cap='合成：自动与人工切换组合，评分为场景设定',security_cap='合成：资产、网络、演练能力评分为场景设定')
    existing['unavailable_fields']=[]
# Replace the old Sep 29 snapshots with the complete daily snapshots (no duplicate keys).
ind['t_indicator_accessed_api_info']['items'][:]=[r for r in ind['t_indicator_accessed_api_info']['items'] if r['source_ref'].startswith(PREFIX)]
path.write_text(json.dumps(f,ensure_ascii=False,indent=2)+'\n')
print('Generated',len(f['running']),'running rows;',len(f['changes']),'changes;',len(f['faults']),'faults')
