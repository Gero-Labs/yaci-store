#!/usr/bin/env python3
"""Execute immutable operator SQL against isolated, disposable PostgreSQL 16."""
from pathlib import Path
from decimal import Decimal
import hashlib
import json
import subprocess
import time
import argparse
import tempfile
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-directory', type=Path, help='Evidence directory outside the repository; defaults to a temporary directory')
options = parser.parse_args()
ROOT = (options.output_directory or Path(tempfile.mkdtemp(prefix='yaci-epoch-repair-validation-'))).resolve()
KIT = Path(__file__).resolve().parents[1]
repository = KIT.parents[2]
if ROOT == repository or repository in ROOT.parents:
    parser.error('--output-directory must be outside the repository')
ROOT.mkdir(parents=True, exist_ok=True)
CONTAINER = 'codex-epoch-repair-clone-' + uuid.uuid4().hex[:12]
LOGS = ROOT / 'logs'
LOGS.mkdir(exist_ok=True)
RESULTS = []
HASHES = {name: hashlib.sha256((KIT / name).read_bytes()).hexdigest() for name in [
    '03-yaci-transaction-window.sql', '04-yaci-applied-output-window.sql',
    '06-yaci-invalid-semantics-window.sql', '07-dbsync-transaction-window.sql',
    '08-block-repair-candidates.sql', '09-apply-reviewed-block-window.sql', '10-apply-reviewed-epoch.sql']}

def command(args, stdin=None):
    return subprocess.run(args, input=stdin, text=True, capture_output=True)

def psql(sql, variables=None, label=None):
    args = ['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-U', 'postgres', '-d', 'repair_test', '-At', '-F', '|', '-v', 'ON_ERROR_STOP=1']
    for key, value in (variables or {}).items():
        args.extend(['-v', f'{key}={value}'])
    result = command(args, sql)
    if label:
        (LOGS / f'{label}.stdout.txt').write_text(result.stdout)
        (LOGS / f'{label}.stderr.txt').write_text(result.stderr)
    return result

def execute(name, variables=None, label=None):
    return psql((KIT / name).read_text(), variables, label)

def scalar(sql):
    r = psql(sql)
    assert r.returncode == 0, r.stderr
    return r.stdout.strip()

def h(label):
    return hashlib.sha256(label.encode()).hexdigest()

def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, bool):
        return 'TRUE' if value else 'FALSE'
    if isinstance(value, (dict, list)):
        return "'" + json.dumps(value).replace("'", "''") + "'::jsonb"
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    return str(value)

def insert(table, rows):
    columns = list(rows[0])
    values = ',\n'.join('(' + ','.join(literal(row[c]) for c in columns) + ')' for row in rows)
    return f'INSERT INTO {table} ({",".join(columns)}) VALUES\n{values};\n'

BLOCKS = [
    dict(hash=h('b100'), number=100, slot=1000, epoch=633, block_time=10000, prev_hash=h('b99'), no_of_txs=1, total_fees=200000, total_output=10000000),
    dict(hash=h('b101'), number=101, slot=1020, epoch=633, block_time=10020, prev_hash=h('b100'), no_of_txs=2, total_fees=400000, total_output=105000000),
    dict(hash=h('b102'), number=102, slot=1040, epoch=633, block_time=10040, prev_hash=h('b101'), no_of_txs=1, total_fees=200000, total_output=100000000),
    dict(hash=h('b103'), number=103, slot=1060, epoch=633, block_time=10060, prev_hash=h('b102'), no_of_txs=1, total_fees=2000000, total_output=1000000),
    dict(hash=h('b104'), number=104, slot=1080, epoch=633, block_time=10080, prev_hash=h('b103'), no_of_txs=0, total_fees=0, total_output=0),
    dict(hash=h('tip'), number=1000, slot=20000, epoch=634, block_time=29000, prev_hash=h('before-tip'), no_of_txs=0, total_fees=0, total_output=0),
]

def outpoint(tx, index):
    return {'tx_hash': h(tx), 'output_index': index}

def output(amount):
    return {'address': 'fixture-address', 'amounts': [{'unit': 'lovelace', 'quantity': amount}]}

def transaction(label, block_index, tx_index, invalid, fee, ordinary_amount, total_collateral=None, collateral_return=None, collateral_inputs=None):
    b = BLOCKS[block_index]
    return dict(tx_hash=h(label), block_hash=b['hash'], block=b['number'], slot=b['slot'], epoch=b['epoch'],
                block_time=b['block_time'], tx_index=tx_index, invalid=invalid, fee=fee,
                outputs=[outpoint(label, 0)], collateral_return_json=None if collateral_return is None else output(collateral_return),
                collateral_inputs=[] if collateral_inputs is None else collateral_inputs, total_collateral=total_collateral)

TXS = [
    transaction('valid-a', 0, 0, False, 200000, 10000000),
    transaction('invalid-babbage', 1, 0, True, 3000000, 100000000, 3000000, 7000000, [outpoint('collateral-b', 0)]),
    transaction('valid-b', 1, 1, False, 200000, 5000000),
    transaction('invalid-alonzo', 2, 0, True, 8000000, 100000000, None, None, [outpoint('collateral-a', 0)]),
    transaction('invalid-control', 3, 0, True, 2000000, 9000000, 2000000, 1000000, [outpoint('collateral-control', 0)]),
]

def utxo(tx, index, amount, collateral=False, tx_row=None):
    if tx_row is None:
        return dict(tx_hash=h(tx), output_index=index, block_hash=h('b99'), block=99, slot=980, epoch=632,
                    block_time=9980, lovelace_amount=amount, is_collateral_return=collateral)
    return dict(tx_hash=tx_row['tx_hash'], output_index=index, block_hash=tx_row['block_hash'], block=tx_row['block'],
                slot=tx_row['slot'], epoch=tx_row['epoch'], block_time=tx_row['block_time'],
                lovelace_amount=amount, is_collateral_return=collateral)

UTXOS = [utxo('valid-a', 0, 10000000, tx_row=TXS[0]), utxo('invalid-babbage', 1, 7000000, True, TXS[1]),
         utxo('valid-b', 0, 5000000, tx_row=TXS[2]), utxo('invalid-control', 1, 1000000, True, TXS[4]),
         utxo('collateral-b', 0, 10000000), utxo('collateral-a', 0, 8000000), utxo('collateral-control', 0, 3000000)]
INVALID_LOGS = []
for index, label, declared_amount, declared_fee in [(1, 'invalid-babbage', 100000000, 200000), (3, 'invalid-alonzo', 100000000, 200000), (4, 'invalid-control', 9000000, 200000)]:
    tx = TXS[index]
    body = dict(txHash=tx['tx_hash'], fee=declared_fee, totalCollateral=tx['total_collateral'],
                outputs=[output(declared_amount)], collateralReturn=tx['collateral_return_json'])
    INVALID_LOGS.append(dict(tx_hash=tx['tx_hash'], slot=tx['slot'], block_hash=tx['block_hash'],
                            transaction={'txHash': tx['tx_hash'], 'invalid': True, 'body': body}))

SCHEMA = '''
DROP TABLE IF EXISTS public.tx;
DROP TABLE IF EXISTS public.block;
DROP SCHEMA IF EXISTS bfcompat_epoch_repair_20261013 CASCADE;
DROP SCHEMA IF EXISTS yaci_store CASCADE;
CREATE SCHEMA yaci_store;
CREATE TABLE yaci_store.block(hash varchar(64) PRIMARY KEY, number bigint, slot bigint, epoch integer,
 block_time bigint, prev_hash varchar(64), no_of_txs integer, total_fees bigint, total_output numeric(38),
 create_datetime timestamp DEFAULT now(), update_datetime timestamp DEFAULT now());
CREATE INDEX ON yaci_store.block(epoch); CREATE INDEX ON yaci_store.block(slot); CREATE INDEX ON yaci_store.block(number);
CREATE TABLE yaci_store.transaction(tx_hash varchar(64) PRIMARY KEY, block_hash varchar(64), block bigint,
 slot bigint, epoch integer, block_time bigint, tx_index integer, invalid boolean, fee bigint,
 outputs jsonb, collateral_return_json jsonb, collateral_inputs jsonb, total_collateral bigint);
CREATE INDEX ON yaci_store.transaction(epoch); CREATE INDEX ON yaci_store.transaction(slot);
CREATE TABLE yaci_store.address_utxo(tx_hash varchar(64), output_index integer, block_hash varchar(64), block bigint,
 slot bigint, epoch integer, block_time bigint, lovelace_amount numeric(38), is_collateral_return boolean,
 PRIMARY KEY(tx_hash, output_index));
CREATE INDEX ON yaci_store.address_utxo(slot);
CREATE TABLE yaci_store.invalid_transaction(tx_hash varchar(64) PRIMARY KEY, slot bigint, block_hash varchar(64), transaction jsonb);
CREATE INDEX ON yaci_store.invalid_transaction(slot);
CREATE TABLE yaci_store.epoch(number bigint PRIMARY KEY, block_count integer, transaction_count bigint,
 total_fees bigint, total_output numeric(38), start_time bigint, end_time bigint, max_slot bigint,
 create_datetime timestamp DEFAULT now(), update_datetime timestamp DEFAULT now());
CREATE TABLE public.block(id bigint PRIMARY KEY,block_no bigint,hash bytea,slot_no bigint,epoch_no integer,time timestamp);
CREATE TABLE public.tx(id bigint PRIMARY KEY,block_id bigint,block_index integer,hash bytea,fee bigint,out_sum numeric,valid_contract boolean);
'''
EPOCH = dict(number=633, block_count=2, transaction_count=2, total_fees=4174805,
             total_output=999999999, start_time=10060, end_time=10020, max_slot=1020)
FIXTURE = SCHEMA + insert('yaci_store.block', BLOCKS) + insert('yaci_store.transaction', TXS) + insert('yaci_store.address_utxo', UTXOS) + insert('yaci_store.invalid_transaction', INVALID_LOGS) + insert('yaci_store.epoch', [EPOCH])
FIXTURE += "INSERT INTO public.block SELECT number,number,decode(hash,'hex'),slot,epoch,to_timestamp(block_time) FROM yaci_store.block;\n"
FIXTURE += "INSERT INTO public.tx SELECT row_number() OVER (ORDER BY block,tx_index),block,tx_index,decode(tx_hash,'hex'),fee,(SELECT coalesce(sum(lovelace_amount),0) FROM yaci_store.address_utxo u WHERE u.tx_hash=t.tx_hash),NOT invalid FROM yaci_store.transaction t;\n"
(ROOT / 'fixture.sql').write_text(FIXTURE)

def reset():
    r = psql(FIXTURE)
    assert r.returncode == 0, r.stderr

def snapshot():
    return json.loads(scalar("SELECT jsonb_build_object('blocks',(SELECT jsonb_agg(to_jsonb(b)-'create_datetime'-'update_datetime' ORDER BY number) FROM yaci_store.block b),'epochs',(SELECT jsonb_agg(to_jsonb(e)-'create_datetime'-'update_datetime' ORDER BY number) FROM yaci_store.epoch e),'backup_schema',to_regnamespace('bfcompat_epoch_repair_20261013') IS NOT NULL);"))

def candidates(label='candidates'):
    r = execute('08-block-repair-candidates.sql', {'e':633, 's0':1000, 's1':1080}, label)
    assert r.returncode == 0, r.stderr
    names = ['hash','number','slot','epoch','no_of_txs','original_fee','original_output','candidate_fee','candidate_output',
             'invalid_transactions','transaction_digest','output_digest','source_digest','scope_digest','transaction_errors','output_errors',
             'transaction_count_error','output_count_error','needs_repair']
    rows=[]
    for line in r.stdout.splitlines():
        fields=line.split('|')
        if len(fields)!=len(names): continue
        row=dict(zip(names,fields))
        for k in names:
            if k in ['hash','transaction_digest','output_digest','source_digest','scope_digest']: continue
            if not row[k]: row[k]=None
            elif row[k] in ['t','f']: row[k]=(row[k]=='t')
            else: row[k]=int(Decimal(row[k]))
        rows.append(row)
    assert len(rows)==3, rows
    return rows

def apply_blocks(rows, label, **vars):
    values={'e':633,'s0':1000,'s1':1080,'k':10,'reviewed_json':json.dumps(rows)}
    values.update(vars)
    return execute('09-apply-reviewed-block-window.sql', values, label)

def epoch_manifest():
    actual=json.loads(scalar("SELECT jsonb_build_object('candidate_block_count',count(*)::integer,'candidate_tx_count',sum(no_of_txs),'candidate_fee',sum(total_fees),'candidate_output',sum(total_output),'candidate_start',min(block_time),'candidate_end',max(block_time),'candidate_max_slot',max(slot),'chain_digest',md5(string_agg(number::text||'|'||lower(hash)||'|'||slot::text||'|'||block_time::text||'|'||no_of_txs::text||'|'||lower(prev_hash),E'\\n' ORDER BY number,hash))) FROM yaci_store.block WHERE epoch=633;"))
    original=json.loads(scalar("SELECT to_jsonb(e)-'create_datetime'-'update_datetime' FROM yaci_store.epoch e WHERE number=633;"))
    mapping={'block_count':'original_block_count','transaction_count':'original_tx_count','total_fees':'original_fee','total_output':'original_output','start_time':'original_start','end_time':'original_end','max_slot':'original_max_slot'}
    return dict(number=633, **{v:original[k] for k,v in mapping.items()}, **actual)

def apply_epoch(row, label, **vars):
    values={'e':633,'k':10,'reviewed_epoch_json':json.dumps([row])}
    values.update(vars)
    return execute('10-apply-reviewed-epoch.sql', values, label)

def record(name, passed, details=None, defect=False):
    RESULTS.append(dict(name=name, passed=bool(passed), defect=defect, details=details))
    print(('PASS ' if passed else 'FAIL ') + name, flush=True)

def refusal(name, mutation='', manifest_mutation=None, vars=None, expected_message='source changed'):
    reset(); rows=candidates(name+'-before'); before=snapshot()
    if mutation:
        r=psql(mutation); assert r.returncode==0,r.stderr
        before=snapshot()
    if manifest_mutation: manifest_mutation(rows)
    r=apply_blocks(rows,name,**(vars or {}))
    record(name, r.returncode!=0 and snapshot()==before and expected_message in r.stderr,
           {'exit_code':r.returncode,'stderr':r.stderr.strip()})

def defect_blocks(name, mutation, proof_name, expected_field):
    reset(); rows=candidates(name+'-before')
    r=psql(mutation); assert r.returncode==0,r.stderr
    r=apply_blocks(rows,name)
    proof=execute(proof_name,{'e':633,'s0':1000,'s1':1080},name+'-postproof')
    record(name, r.returncode!=0, {'actual_accepted':r.returncode==0,'postproof_stdout':proof.stdout.strip(),
                                 'expected_refusal_for':expected_field}, defect=r.returncode==0)

def start_holder(sql):
    tag='epoch-clone-lock-holder'
    args=['docker','exec','-i',CONTAINER,'psql','-X','-U','postgres','-d','repair_test','-At','-v','ON_ERROR_STOP=1']
    holder=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    holder.stdin.write("SET application_name='"+tag+"'; BEGIN; "+sql+"; SELECT pg_sleep(30); ROLLBACK;\n")
    holder.stdin.close()
    for _ in range(100):
        ready=scalar("SELECT count(*) FROM pg_stat_activity WHERE application_name='"+tag+"' AND wait_event='PgSleep';")
        if ready=='1': return holder,tag
        time.sleep(.02)
    raise RuntimeError('Lock holder failed to become ready')

def release_holder(holder,tag):
    scalar("SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name='"+tag+"';")
    holder.wait(timeout=5)

def lock_refusal(name,table,epoch=False):
    reset(); rows=candidates(name+'-before')
    if epoch:
        r=apply_blocks(rows,name+'-prepare'); assert r.returncode==0,r.stderr
        row=epoch_manifest()
    before=snapshot()
    holder,tag=start_holder('LOCK TABLE '+table+' IN ROW EXCLUSIVE MODE')
    try:
        started=time.monotonic()
        r=apply_epoch(row,name) if epoch else apply_blocks(rows,name)
        elapsed=time.monotonic()-started
        record(name,r.returncode!=0 and 'lock timeout' in r.stderr and elapsed<3 and snapshot()==before,
               {'elapsed_seconds':elapsed,'stderr':r.stderr.strip()})
    finally: release_holder(holder,tag)

def phantom_insert_refusal():
    reset()
    holder,tag=start_holder('LOCK TABLE yaci_store.address_utxo IN SHARE MODE')
    try:
        r=psql("BEGIN; SET LOCAL lock_timeout='1s'; INSERT INTO yaci_store.address_utxo(tx_hash,output_index) VALUES('phantom',0); COMMIT;",label='refuse-phantom-insert-under-share-lock')
        record('share-source-lock-blocks-phantom-inserts',r.returncode!=0 and 'lock timeout' in r.stderr and scalar("SELECT count(*) FROM yaci_store.address_utxo WHERE tx_hash='phantom';")=='0',r.stderr)
    finally: release_holder(holder,tag)

def lock_order_upgrade_refusal():
    reset(); rows=candidates('upgrade-refusal-before'); before=snapshot()
    holder,tag=start_holder('LOCK TABLE yaci_store.block IN SHARE MODE')
    try:
        r=apply_blocks(rows,'refuse-share-to-writer-upgrade-contention')
        record('concurrent-share-holder-refuses-update-upgrade-without-partial-state',r.returncode!=0 and 'lock timeout' in r.stderr and snapshot()==before,r.stderr)
    finally: release_holder(holder,tag)

def main():
    inspected=command(['docker','inspect',CONTAINER])
    if inspected.returncode==0:
        raise RuntimeError('Container name already exists; refusing to overwrite an existing container')
    start=command(['docker','run','--detach','--name',CONTAINER,'--network','none',
                   '--label','codex.task=epoch-repair-clone-validation','--tmpfs','/var/lib/postgresql/data',
                   '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_DB=repair_test','postgres:16'])
    assert start.returncode==0,start.stderr
    (ROOT/'container-id.txt').write_text(start.stdout.strip()+'\n')
    try:
        for _ in range(120):
            r=psql('SELECT 1;')
            status=command(['docker','exec',CONTAINER,'sh','-c','test -f /var/lib/postgresql/data/postmaster.pid && test "$(head -1 /var/lib/postgresql/data/postmaster.pid)" = 1'])
            if r.returncode==0 and status.returncode==0: break
            time.sleep(.25)
        version=scalar('SELECT version();')
        reset(); rows=candidates('baseline-candidates')
        (ROOT/'baseline-reviewed-blocks.json').write_text(json.dumps(rows,indent=2)+'\n')
        amounts={x['hash']:(x['candidate_fee'],x['candidate_output'],x['needs_repair']) for x in rows}
        record('valid-invalid-candidate-calculation', amounts=={h('b101'):(3200000,12000000,True),h('b102'):(8000000,0,True),h('b103'):(2000000,1000000,False)} and h('b100') not in amounts, amounts)
        membership=execute('03-yaci-transaction-window.sql',{'e':633,'s0':1000,'s1':1080},'baseline-transaction-membership')
        applied=execute('04-yaci-applied-output-window.sql',{'e':633,'s0':1000,'s1':1080},'baseline-applied-output-membership')
        independent=execute('07-dbsync-transaction-window.sql',{'e':633,'s0':1000,'s1':1080},'baseline-independent-transactions')
        def output_row(result,length):
            return next(line.split('|') for line in result.stdout.splitlines() if len(line.split('|'))==length)
        yaci=output_row(membership,19); utxo=output_row(applied,13); dbsync=output_row(independent,9)
        record('transaction-output-independent-membership-proofs',membership.returncode==0 and applied.returncode==0 and independent.returncode==0
               and yaci[5]=='5' and yaci[6]==dbsync[8] and yaci[7]==dbsync[4]=='13400000'
               and utxo[5]==dbsync[5]=='23000000' and all(x=='0' for x in yaci[9:])
               and all(x=='0' for x in utxo[6:]),{'yaci':yaci,'outputs':utxo,'dbsync':dbsync})
        proof=execute('06-yaci-invalid-semantics-window.sql',{'e':633,'s0':1000,'s1':1080},'baseline-invalid-semantics')
        record('invalid-babbage-alonzo-source-semantics-query-executes',proof.returncode==0,proof.stdout)
        r=apply_blocks(rows,'success-blocks')
        record('reviewed-block-repair-success',r.returncode==0 and scalar('SELECT count(*) FROM bfcompat_epoch_repair_20261013.block_before;')=='2',r.stderr)
        row=epoch_manifest(); (ROOT/'baseline-reviewed-epoch.json').write_text(json.dumps([row],indent=2)+'\n')
        r=apply_epoch(row,'success-epoch')
        record('reviewed-epoch-repair-success',r.returncode==0 and scalar('SELECT count(*) FROM bfcompat_epoch_repair_20261013.epoch_before;')=='1',r.stderr)
        backup=scalar('SELECT md5((SELECT string_agg(to_jsonb(b)::text,E\'\\n\' ORDER BY hash) FROM bfcompat_epoch_repair_20261013.block_before b)||\'|\'||(SELECT string_agg(to_jsonb(e)::text,E\'\\n\' ORDER BY number) FROM bfcompat_epoch_repair_20261013.epoch_before e));')
        after=snapshot(); (ROOT/'after-repair.json').write_text(json.dumps(after,indent=2)+'\n')
        r=apply_blocks(rows,'repeat-original-blocks')
        record('same-block-manifest-refuses-reapply-without-changing-state',r.returncode!=0 and snapshot()==after,r.stderr)
        r=apply_epoch(row,'repeat-original-epoch')
        record('same-epoch-manifest-refuses-reapply-without-changing-state',r.returncode!=0 and snapshot()==after,r.stderr)
        refreshed=candidates('refreshed-controls'); r=apply_blocks(refreshed,'repeat-refreshed-controls')
        record('refreshed-block-controls-noop-and-preserve-original-backup',r.returncode==0 and snapshot()==after and scalar('SELECT count(*) FROM bfcompat_epoch_repair_20261013.block_before;')=='2',r.stderr)
        r=apply_epoch(epoch_manifest(),'repeat-refreshed-epoch')
        backup_after=scalar('SELECT md5((SELECT string_agg(to_jsonb(b)::text,E\'\\n\' ORDER BY hash) FROM bfcompat_epoch_repair_20261013.block_before b)||\'|\'||(SELECT string_agg(to_jsonb(e)::text,E\'\\n\' ORDER BY number) FROM bfcompat_epoch_repair_20261013.epoch_before e));')
        record('refreshed-epoch-manifest-preserves-original-backup',r.returncode==0 and snapshot()==after and scalar('SELECT count(*) FROM bfcompat_epoch_repair_20261013.epoch_before;')=='1' and backup==backup_after,r.stderr)
        refusal('refuse-block-original-value-change',f"UPDATE yaci_store.block SET total_fees=total_fees+1 WHERE hash='{h('b101')}';")
        refusal('refuse-effective-transaction-source-change',f"UPDATE yaci_store.transaction SET fee=fee+1 WHERE tx_hash='{h('invalid-babbage')}';")
        refusal('refuse-created-output-value-change',f"UPDATE yaci_store.address_utxo SET lovelace_amount=lovelace_amount+1 WHERE tx_hash='{h('invalid-babbage')}';")
        refusal('refuse-transaction-digest-mismatch',manifest_mutation=lambda rows:rows[0].update(transaction_digest='0'*32),expected_message='source changed')
        refusal('refuse-output-digest-mismatch',manifest_mutation=lambda rows:rows[0].update(output_digest='0'*32),expected_message='source changed')
        refusal('refuse-security-depth',vars={'k':896},expected_message='security depth')
        refusal('refuse-open-epoch',mutation="UPDATE yaci_store.block SET epoch=633 WHERE number=1000;",expected_message='not closed')
        refusal('refuse-oversized-window',vars={'s1':11000},expected_message='reviewed bound')
        refusal('refuse-unresolved-null-fee-after-review',mutation=f"UPDATE yaci_store.transaction SET fee=NULL WHERE tx_hash='{h('invalid-babbage')}';")
        reset(); psql(f"UPDATE yaci_store.transaction SET fee=NULL WHERE tx_hash='{h('invalid-babbage')}';")
        bad=candidates('null-fee-candidate'); before=snapshot(); r=apply_blocks(bad,'refuse-unresolved-null-fee-candidate')
        record('refuse-unresolved-null-fee-candidate',r.returncode!=0 and snapshot()==before and 'malformed reviewed' in r.stderr,r.stderr)
        reset(); rows=candidates(); apply_blocks(rows,'epoch-refusal-setup'); row=epoch_manifest()
        psql('UPDATE yaci_store.epoch SET total_fees=total_fees+1 WHERE number=633;'); before=snapshot(); r=apply_epoch(row,'refuse-epoch-original-value-change')
        record('refuse-epoch-original-value-change',r.returncode!=0 and snapshot()==before and 'epoch row changed' in r.stderr,r.stderr)
        reset(); rows=candidates(); apply_blocks(rows,'epoch-digest-setup'); row=epoch_manifest(); row['chain_digest']='0'*32
        before=snapshot(); r=apply_epoch(row,'refuse-epoch-chain-digest-change')
        record('refuse-epoch-chain-digest-change',r.returncode!=0 and snapshot()==before and 'source changed' in r.stderr,r.stderr)
        reset(); rows=candidates(); apply_blocks(rows,'epoch-depth-setup'); row=epoch_manifest(); before=snapshot(); r=apply_epoch(row,'refuse-epoch-security-depth',k=896)
        record('refuse-epoch-security-depth',r.returncode!=0 and snapshot()==before and 'mutable epoch' in r.stderr,r.stderr)
        defect_blocks('defect-reviewed-output-shape-change-escapes-09',f"UPDATE yaci_store.transaction SET outputs='[]'::jsonb WHERE tx_hash='{h('invalid-babbage')}';",'04-yaci-applied-output-window.sql','wrong_applied_output_semantics')
        defect_blocks('defect-reviewed-output-epoch-change-escapes-09',f"UPDATE yaci_store.address_utxo SET epoch=632 WHERE tx_hash='{h('invalid-babbage')}';",'04-yaci-applied-output-window.sql','wrong_epoch_outputs and bad_output_metadata')
        defect_blocks('defect-reviewed-invalid-body-change-escapes-09',f"UPDATE yaci_store.invalid_transaction SET transaction=jsonb_set(transaction,'{{body,totalCollateral}}','1'::jsonb) WHERE tx_hash='{h('invalid-babbage')}';",'06-yaci-invalid-semantics-window.sql','total_collateral_body_mismatches')
        reset(); rows=candidates(); apply_blocks(rows,'epoch-null-source-setup'); row=epoch_manifest()
        psql("UPDATE yaci_store.block SET total_fees=NULL,total_output=NULL WHERE number=104;")
        r=apply_epoch(row,'defect-reviewed-null-zero-block-source-escapes-10')
        record('defect-reviewed-null-zero-block-source-escapes-10',r.returncode!=0,{'actual_accepted':r.returncode==0,'stderr':r.stderr},defect=r.returncode==0)
        defect_blocks('defect-new-zero-output-wrong-block-escapes-09',f"INSERT INTO yaci_store.address_utxo(tx_hash,output_index,block_hash,block,slot,epoch,block_time,lovelace_amount,is_collateral_return) VALUES('{h('invalid-babbage')}',9,'wrong-block',101,1020,633,10020,0,FALSE);",'04-yaci-applied-output-window.sql','extra output membership')
        refusal('refuse-canonical-transaction-moved-outside-slot-window',mutation=f"UPDATE yaci_store.transaction SET slot=5000 WHERE tx_hash='{h('invalid-babbage')}';")
        refusal('refuse-entirely-orphan-zero-output-in-window',mutation="INSERT INTO yaci_store.address_utxo(tx_hash,output_index,block_hash,block,slot,epoch,block_time,lovelace_amount,is_collateral_return) VALUES('orphan',0,'wrong-block',101,1020,633,10020,0,FALSE);")
        refusal('refuse-output-outside-window-for-canonical-transaction',mutation=f"INSERT INTO yaci_store.address_utxo(tx_hash,output_index,block_hash,block,slot,epoch,block_time,lovelace_amount,is_collateral_return) VALUES('{h('invalid-babbage')}',9,'wrong-block',101,5000,633,10020,0,FALSE);")
        refusal('refuse-entirely-orphan-invalid-log-in-window',mutation="INSERT INTO yaci_store.invalid_transaction(tx_hash,slot,block_hash,transaction) VALUES('orphan-log',1020,'wrong-block','{}'::jsonb);")
        reset(); rows=candidates('epoch-fraction-before'); apply_blocks(rows,'epoch-fraction-setup'); row=epoch_manifest()
        row['candidate_output']=row['candidate_output']+0.5
        r=apply_epoch(row,'refuse-fractional-epoch-output-manifest')
        record('refuse-fractional-epoch-output-manifest',r.returncode!=0 and 'mutable epoch candidate' in r.stderr,{'stderr':r.stderr})
        row=epoch_manifest(); row['candidate_fee']=row['candidate_fee']+0.5
        r=apply_epoch(row,'refuse-fractional-epoch-fee-manifest')
        record('refuse-fractional-epoch-fee-manifest',r.returncode!=0 and 'mutable epoch candidate' in r.stderr,{'stderr':r.stderr})
        for table in ['yaci_store.block','yaci_store.transaction','yaci_store.address_utxo','yaci_store.invalid_transaction']:
            lock_refusal('refuse-held-writer-'+table.split('.')[-1],table)
        lock_refusal('refuse-held-writer-during-epoch-repair','yaci_store.block',epoch=True)
        phantom_insert_refusal()
        lock_order_upgrade_refusal()
        result=dict(postgresql_version=version,container_network='none',production_contacted=False,
                    sql_sha256=HASHES,cases=RESULTS,passed=sum(x['passed'] for x in RESULTS),
                    failed=sum(not x['passed'] for x in RESULTS),defects=sum(x['defect'] for x in RESULTS))
        (ROOT/'results.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({k:result[k] for k in ['passed','failed','defects']},indent=2),flush=True)
        if result['failed']:
            raise SystemExit(1)
    finally:
        cleanup=command(['docker','rm','--force',CONTAINER])
        (ROOT/'cleanup.txt').write_text(cleanup.stdout+cleanup.stderr)

if __name__=='__main__':
    main()
