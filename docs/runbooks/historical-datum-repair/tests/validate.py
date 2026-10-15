#!/usr/bin/env python3
"""Local-only proof of manifest decisions and exact guarded apply SQL."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock

KIT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0,str(KIT))
import repair
import source_inventory
from raw_cbor import datum, witness_datums, Invalid

parser=argparse.ArgumentParser()
parser.add_argument('--container', required=True)
parser.add_argument('--evidence', type=Path, required=True)
options=parser.parse_args()
if not re.fullmatch(r'[0-9a-f]{12,64}',options.container):
    parser.error('Use a Testcontainers fixture container ID')
TOKEN=os.environ.get('YACI_DATUM_REPAIR_FIXTURE_TOKEN','')
if not re.fullmatch(r'[0-9a-f]{32}',TOKEN):
    parser.error('The Testcontainers fixture marker is required')
ROOT=options.evidence.resolve();ROOT.mkdir(parents=True,exist_ok=True)
repository=KIT.parents[2]
if ROOT==repository or repository in ROOT.parents:
    parser.error('Evidence must be outside the repository')

def sql(source):
    result=subprocess.run(['docker','exec','-i',options.container,'psql','-X','-At','-U','datum_repair_test','-d','datum_repair_test','-v','ON_ERROR_STOP=1'],input=source,text=True,capture_output=True,timeout=20)
    return result

def query(source,write=False):
    result=sql(source)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return [json.loads(line) for line in result.stdout.splitlines() if line.startswith(('{','['))]
repair.query=query

# Never execute the first destructive fixture unless both the owning runtime and
# the random database marker match the enclosing JUnit Testcontainers fixture.
inspected=subprocess.run(['docker','inspect',options.container],text=True,capture_output=True,timeout=10)
if inspected.returncode:raise RuntimeError('Fixture container unavailable')
info=json.loads(inspected.stdout)[0]
if (info.get('Config',{}).get('Labels') or {}).get('org.testcontainers')!='true':
    raise RuntimeError('Refusing a container not owned by Testcontainers')
marker=query('SELECT row_to_json(m) FROM public.codex_datum_repair_test_fixture m')
if marker!=[dict(token=TOKEN)]:raise RuntimeError('Fixture marker does not match; refusing destructive setup')
(ROOT/'container-id.txt').write_text(options.container+'\n')

fixture='''DROP SCHEMA IF EXISTS yaci_store CASCADE;DROP SCHEMA IF EXISTS bfcompat_datum_repair_20261015 CASCADE;
CREATE SCHEMA yaci_store;
CREATE TABLE yaci_store.datum(hash varchar(64) PRIMARY KEY,datum text,created_at_tx varchar(64),slot bigint,create_datetime timestamp,update_datetime timestamp);
CREATE TABLE yaci_store.transaction(tx_hash varchar(64) PRIMARY KEY,slot bigint,tx_index integer);
CREATE TABLE yaci_store.address_utxo(tx_hash varchar(64),output_index smallint,slot bigint,inline_datum text,data_hash text,PRIMARY KEY(output_index,tx_hash));
CREATE TABLE yaci_store.transaction_cbor(tx_hash varchar(64) PRIMARY KEY,slot bigint,cbor_data bytea);
CREATE TABLE yaci_store.transaction_scripts(id uuid,datum_hash varchar(64));
'''
old='1'*64;tx='a'*64

def entry(raw='1800',hash_=old,slot=None,proof=True):
    row=dict(hash=hash_,datum=raw,created_at_tx=tx,slot=slot,create_datetime='2026-01-01T00:00:00',update_datetime='2026-01-01T00:00:00')
    e=dict(network='preview',before=row,before_fingerprint=repair.fingerprint(row),raw_hash=datum(raw),classification='retained_wrong_key' if hash_!=datum(raw) else 'missing_provenance')
    if proof:e['provenance']=dict(kind='inline',tx_hash=tx,slot=100,tx_index=1,output_index=0,cbor=raw)
    return e

def insert(e):
    r=e['before']
    return "INSERT INTO yaci_store.datum VALUES("+','.join(repair.literal(r.get(k)) for k in ['hash','datum','created_at_tx','slot','create_datetime','update_datetime'])+");\n"

def batch(entries):
    value=dict(format=1,network='preview',cursor_before='',cursor_after=entries[-1]['before']['hash'],entries=entries)
    path=ROOT/'manifest.json';path.write_text(repair.canonical(value)+'\n')
    return path,repair.fingerprint(value)

def apply(entries):
    path,digest=batch(entries)
    output=ROOT/'apply.sql'
    repair.apply(argparse.Namespace(manifest=path,sha256=digest,network='preview',sql_output=output))
    result=sql(output.read_text());ROOT.joinpath('latest.stdout').write_text(result.stdout);ROOT.joinpath('latest.stderr').write_text(result.stderr)
    return result

class Acceptance(unittest.TestCase):
    def setUp(self):
        self.assertEqual(sql(fixture).returncode,0)
    def test_raw_byte_vectors_and_plutus_validation(self):
        self.assertEqual(datum('00'),'03170a2e7597b7b7e3d84c05391d139a62b157e78786d8c082f29dcf4c111314')
        self.assertEqual(datum('1800'),'d6151a4af5dbab50abc915c1a41e5bee160989792ae1ca5d8dd7c6294ad04bc0')
        self.assertNotEqual(datum('9f00ff'),datum('8100'))
        for invalid in ['','f6','0000','d879','41','not-hex','00 00']:
            with self.assertRaises(Invalid):datum(invalid)
    def test_witness_source_uses_original_bytes_and_verifies_source_tx_identity(self):
        # body {}, witnesses {4: tag258([nonminimal integer0])}, validitytrue, auxnull
        txraw='84a0a104d90102811800f5f6'
        import hashlib
        txid=hashlib.blake2b(bytes.fromhex('a0'),digest_size=32).hexdigest()
        values=witness_datums(txraw,txid)
        self.assertEqual(values,[dict(index=0,cbor='1800',raw_hash=datum('1800'))])
        with self.assertRaises(Invalid):witness_datums(txraw,'0'*64)
        for invalid in ['not-hex','84a0a10481180000f6','84a0a104811800f5f5','a0']:
            with self.assertRaises(Invalid):witness_datums(invalid,txid)
        e=entry(proof=False);e['before']['created_at_tx']=txid;e['before_fingerprint']=repair.fingerprint(e['before'])
        sql(insert(e)+f"INSERT INTO yaci_store.transaction VALUES('{txid}',100,1);INSERT INTO yaci_store.transaction_cbor VALUES('{txid}',100,decode('{txraw}','hex'));")
        proof,debt=repair.provenance(e['before']);self.assertIsNone(debt);self.assertEqual(proof['kind'],'witness')
        e['provenance']=proof;result=apply([e]);self.assertEqual(result.returncode,0,result.stderr)
    def test_missing_optional_witness_table_is_explicit_debt(self):
        e=entry(proof=False);sql(insert(e)+f"INSERT INTO yaci_store.transaction VALUES('{tx}',100,1);DROP TABLE yaci_store.transaction_cbor;")
        proof,debt=repair.provenance(e['before']);self.assertIsNone(proof);self.assertEqual(debt,'source_transaction_cbor_unavailable')
    def test_inline_provenance_does_not_require_optional_witness_table(self):
        e=entry();sql(insert(e)+f"INSERT INTO yaci_store.transaction VALUES('{tx}',100,1);INSERT INTO yaci_store.address_utxo VALUES('{tx}',0,100,'1800',NULL);DROP TABLE yaci_store.transaction_cbor;")
        proof,debt=repair.provenance(e['before']);self.assertIsNone(debt);self.assertEqual(proof['kind'],'inline')
        e['provenance']=proof;result=apply([e]);self.assertEqual(result.returncode,0,result.stderr)
    def test_apply_preserves_legacy_exact_bytes_and_proves_null_slot_then_rerun(self):
        e=entry();self.assertEqual(sql(insert(e)+f"INSERT INTO yaci_store.transaction VALUES('{tx}',100,1);INSERT INTO yaci_store.address_utxo VALUES('{tx}',0,100,'1800',NULL);").returncode,0)
        result=apply([e]);self.assertEqual(result.returncode,0,result.stderr)
        rows=query('SELECT row_to_json(d) FROM yaci_store.datum d ORDER BY hash')
        self.assertEqual(len(rows),2);self.assertTrue(all(r['datum']=='1800' and r['slot']==100 for r in rows))
        self.assertEqual(apply([e]).returncode,0)
        journal=query('SELECT row_to_json(j) FROM bfcompat_datum_repair_20261015.journal j')
        self.assertEqual(len(journal),1);self.assertTrue(journal[0]['inserted']);self.assertIsNone(journal[0]['old_slot'])
    def test_wrong_source_occurrence_aborts_without_mutation(self):
        e=entry();sql(insert(e)+f"INSERT INTO yaci_store.transaction VALUES('{tx}',101,1);INSERT INTO yaci_store.address_utxo VALUES('{tx}',0,100,'1800',NULL);")
        result=apply([e]);self.assertNotEqual(result.returncode,0);self.assertIn('Provenance source changed',result.stderr)
        self.assertEqual(len(query('SELECT row_to_json(d) FROM yaci_store.datum d')),1)
    def test_raw_key_conflict_and_source_change_abort_entire_batch(self):
        e=entry(proof=False);other=entry(raw='00',hash_=datum('1800'),slot=20,proof=False)
        sql(insert(e)+insert(other));result=apply([e]);self.assertNotEqual(result.returncode,0);self.assertIn('Raw key byte conflict',result.stderr)
        sql('TRUNCATE yaci_store.datum;'+insert(e)+f"UPDATE yaci_store.datum SET slot=3 WHERE hash='{old}';")
        result=apply([e]);self.assertNotEqual(result.returncode,0);self.assertIn('Retained row changed',result.stderr)
    def test_missing_provenance_keeps_null_and_existing_identical_target_is_not_replaced(self):
        e=entry(proof=False);sql(insert(e));self.assertEqual(apply([e]).returncode,0)
        rows=query('SELECT row_to_json(d) FROM yaci_store.datum d')
        self.assertTrue(all(r['slot'] is None for r in rows))
        result=apply([e]);self.assertEqual(result.returncode,0,result.stderr)
    def test_discovery_is_lexicographic_resumable_and_retains_missing_provenance_debt(self):
        for hash_ in ['3'*64,'1'*64,'2'*64]:sql(insert(entry(hash_=hash_,proof=False)))
        directory=ROOT/'discovery';directory.mkdir(exist_ok=True)
        for p in directory.iterdir():p.unlink()
        for _ in range(4):repair.discover(argparse.Namespace(directory=directory,network='preview',count=1))
        state=json.loads((directory/'cursor.json').read_text());self.assertTrue(state['complete']);self.assertEqual(state['cursor'],'3'*64)
        manifests=[json.loads(p.read_text()) for p in sorted(directory.glob('batch-*.json'))]
        self.assertEqual([m['entries'][0]['before']['hash'] for m in manifests if m['entries']],['1'*64,'2'*64,'3'*64])
        self.assertTrue(all(m['entries'][0]['debt']=='source_transaction_unavailable' for m in manifests if m['entries']))
    def test_rollback_preserves_legacy_and_restores_only_unchanged_provenance(self):
        e=entry();sql(insert(e)+f"INSERT INTO yaci_store.transaction VALUES('{tx}',100,1);INSERT INTO yaci_store.address_utxo VALUES('{tx}',0,100,'1800',NULL);")
        self.assertEqual(apply([e]).returncode,0)
        path,digest=batch([e]);output=ROOT/'rollback.sql'
        repair.rollback(argparse.Namespace(sha256=digest,sql_output=output))
        result=sql(output.read_text());self.assertEqual(result.returncode,0,result.stderr)
        rows=query('SELECT row_to_json(d) FROM yaci_store.datum d');self.assertEqual(len(rows),1);self.assertEqual(rows[0]['hash'],old);self.assertIsNone(rows[0]['slot'])
        self.assertEqual(sql(output.read_text()).returncode,0)
    def test_rollback_refuses_changed_alias_or_new_reference(self):
        e=entry(proof=False);sql(insert(e));self.assertEqual(apply([e]).returncode,0)
        path,digest=batch([e]);output=ROOT/'rollback.sql';repair.rollback(argparse.Namespace(sha256=digest,sql_output=output))
        sql(f"INSERT INTO yaci_store.address_utxo VALUES('{tx}',0,100,'1800','{e['raw_hash']}');")
        result=sql(output.read_text());self.assertNotEqual(result.returncode,0);self.assertIn('acquired a reference',result.stderr)
        sql(f"DELETE FROM yaci_store.address_utxo;UPDATE yaci_store.datum SET slot=9 WHERE hash='{e['raw_hash']}';")
        result=sql(output.read_text());self.assertNotEqual(result.returncode,0);self.assertIn('row changed',result.stderr)
    def test_rollback_refuses_unspent_current_and_unknown_reference_tables(self):
        e=entry(proof=False);sql(insert(e));self.assertEqual(apply([e]).returncode,0)
        path,digest=batch([e]);output=ROOT/'rollback.sql';repair.rollback(argparse.Namespace(sha256=digest,sql_output=output))
        for table in ['address_utxo_unspent','address_utxo_current']:
            sql(f"CREATE TABLE yaci_store.{table}(data_hash text);INSERT INTO yaci_store.{table} VALUES('{e['raw_hash']}');")
            result=sql(output.read_text());self.assertNotEqual(result.returncode,0);self.assertIn('acquired a reference',result.stderr)
            sql(f'DROP TABLE yaci_store.{table};')
        sql('CREATE TABLE yaci_store.extra_reference(datum_hash text);')
        result=sql(output.read_text());self.assertNotEqual(result.returncode,0);self.assertIn('Unknown datum reference table',result.stderr)
    def test_source_inventory_uses_bounded_output_primary_key_cursor_and_exact_witness_bytes(self):
        sql(f"INSERT INTO yaci_store.address_utxo VALUES('{tx}',1,101,NULL,NULL),('{'b'*64}',0,100,'1800',NULL),('{'c'*64}',0,102,'00',NULL);")
        directory=ROOT/'output-inventory';directory.mkdir(exist_ok=True)
        for p in directory.iterdir():p.unlink()
        for _ in range(4):
            with mock.patch.object(sys,'argv',['source_inventory','--source','output','--network','preview','--directory',str(directory),'--count','1']):source_inventory.main()
        batches=[json.loads(p.read_text()) for p in sorted(directory.glob('batch-*.json'))]
        self.assertEqual([b['rows_read'] for b in batches],[1,1,1,0]);self.assertEqual([b['entries'][0]['cbor'] for b in batches if b['entries']],['1800','00'])
        state=json.loads((directory/'cursor.json').read_text());self.assertTrue(state['complete']);self.assertEqual((state['index'],state['hash']),(1,tx))
        import hashlib
        raw='84a0a104d90102811800f5f6';txid=hashlib.blake2b(bytes.fromhex('a0'),digest_size=32).hexdigest()
        sql(f"INSERT INTO yaci_store.transaction_cbor VALUES('{txid}',103,decode('{raw}','hex'));")
        directory=ROOT/'witness-inventory';directory.mkdir(exist_ok=True)
        for p in directory.iterdir():p.unlink()
        with mock.patch.object(sys,'argv',['source_inventory','--source','witness','--network','preview','--directory',str(directory),'--count','1']):source_inventory.main()
        batch_=json.loads(next(directory.glob('batch-*.json')).read_text());self.assertEqual(batch_['entries'][0]['cbor'],'1800');self.assertEqual(batch_['entries'][0]['raw_hash'],datum('1800'))
    def test_source_inventory_records_oversized_rows_without_materializing_bytes(self):
        sql(f"INSERT INTO yaci_store.address_utxo VALUES('{tx}',0,100,repeat('00',1048577),NULL);")
        directory=ROOT/'oversized-inventory';directory.mkdir(exist_ok=True)
        for p in directory.iterdir():p.unlink()
        with mock.patch.object(sys,'argv',['source_inventory','--source','output','--network','preview','--directory',str(directory),'--count','1']):source_inventory.main()
        batch_=json.loads(next(directory.glob('batch-*.json')).read_text());self.assertEqual(batch_['entries'][0]['classification'],'source_byte_budget_exceeded');self.assertNotIn('cbor',batch_['entries'][0])
    def test_apply_globally_orders_keys_and_rejects_discovery_only_sample_format(self):
        e=entry(proof=False);second=entry(raw='00',hash_='2'*64,proof=False)
        path,digest=batch([second,e]);output=ROOT/'key-order.sql'
        repair.apply(argparse.Namespace(manifest=path,sha256=digest,network='preview',sql_output=output))
        source=output.read_text();self.assertEqual(source.count("pg_advisory_xact_lock(lock_id)"),1)
        self.assertLess(source.index('pg_advisory_xact_lock'),source.index('DO $entry$'))
        keys=sorted({e['before']['hash'],e['raw_hash'],second['before']['hash'],second['raw_hash']})
        self.assertIn('unnest(ARRAY['+','.join(repair.literal(k) for k in keys)+'])v ORDER BY id LOOP',source)
        value=json.loads(path.read_text());value['format']='discovery-only-samples-v1';path.write_text(repair.canonical(value))
        with self.assertRaisesRegex(ValueError,'Unsupported repair manifest format'):
            repair.apply(argparse.Namespace(manifest=path,sha256=repair.fingerprint(value),network='preview',sql_output=output))
    def test_apply_and_rollback_deduplicate_and_order_actual_colliding_advisory_ids(self):
        # Different reviewed datum aliases can map to one hashtext advisory ID.
        # Record the actual generated lock call sequence; text ordering is insufficient.
        collision=query("""WITH candidates AS MATERIALIZED (
          SELECT md5(g::text)||md5('alias-'||g::text) AS v,
           hashtext('datum-repair:'||md5(g::text)||md5('alias-'||g::text)) AS lock_id
          FROM generate_series(1,250000)g),
         collision AS (SELECT lock_id FROM candidates GROUP BY lock_id HAVING count(*)>1 ORDER BY lock_id LIMIT 1)
         SELECT json_build_object('keys',array_agg(v ORDER BY v),'lock_id',lock_id)
         FROM candidates JOIN collision USING(lock_id) GROUP BY lock_id""")
        self.assertEqual(len(collision),1,'Deterministic PostgreSQL aliases must expose a real hashtext collision')
        aliases=collision[0]['keys'][:2]
        self.assertEqual(len(aliases),2)
        entries=[entry(raw='1800',hash_=aliases[0],proof=False),entry(raw='00',hash_=aliases[1],proof=False)]
        self.assertEqual(sql(''.join(insert(e) for e in entries)).returncode,0)
        path,digest=batch(entries);apply_output=ROOT/'collision-apply.sql';rollback_output=ROOT/'collision-rollback.sql'
        repair.apply(argparse.Namespace(manifest=path,sha256=digest,network='preview',sql_output=apply_output))
        repair.rollback(argparse.Namespace(sha256=digest,sql_output=rollback_output))
        # Seed only the immutable keys consumed by rollback's generated lock query.
        seed="CREATE SCHEMA bfcompat_datum_repair_20261015; CREATE TABLE bfcompat_datum_repair_20261015.journal(manifest_sha256 text,entry_key text,raw_hash text);"
        for e in entries:seed+='INSERT INTO bfcompat_datum_repair_20261015.journal VALUES('+','.join(repair.literal(v) for v in [digest,e['before']['hash'],e['raw_hash']])+');'
        self.assertEqual(sql(seed).returncode,0)
        keys=sorted({key for e in entries for key in [e['before']['hash'],e['raw_hash']]})
        expected=query("SELECT json_agg(lock_id ORDER BY lock_id) AS ids FROM (SELECT DISTINCT hashtext('datum-repair:'||v) AS lock_id FROM unnest(ARRAY["+','.join(repair.literal(k) for k in keys)+"])v)actual")[0]
        self.assertLess(len(expected),len(keys),'The fixture must exercise deduplication of actual numeric IDs')
        def recorded(source):
            import re
            match=re.search(r'FOR lock_id IN SELECT DISTINCT[\s\S]+?END LOOP;',source)
            self.assertIsNotNone(match,'The generated operator SQL must acquire all locks before row changes')
            statement="DO $probe$ DECLARE lock_id bigint; BEGIN "+match[0].replace('pg_advisory_xact_lock','pg_temp.record_lock')+" END $probe$;"
            probe="""CREATE TEMP TABLE lock_calls(sequence bigserial,lock_id bigint);
             CREATE FUNCTION pg_temp.record_lock(value bigint) RETURNS void LANGUAGE plpgsql AS $f$
             BEGIN INSERT INTO lock_calls(lock_id) VALUES(value); END $f$;
             """+statement+' SELECT json_agg(lock_id ORDER BY sequence) AS ids FROM lock_calls;'
            return query(probe)[0]
        self.assertEqual(recorded(apply_output.read_text()),expected,'Apply must order distinct actual advisory IDs')
        self.assertEqual(recorded(rollback_output.read_text()),expected,'Rollback must order distinct actual advisory IDs')
    def test_reviewed_digest_and_raw_hash_cannot_be_substituted(self):
        e=entry(proof=False);sql(insert(e));path,digest=batch([e]);e['raw_hash']='0'*64;path.write_text(repair.canonical(json.loads(path.read_text())|{'entries':[e]}))
        with self.assertRaises(ValueError):repair.apply(argparse.Namespace(manifest=path,sha256=digest,network='preview',sql_output=ROOT/'bad.sql'))

suite=unittest.defaultTestLoader.loadTestsFromTestCase(Acceptance)
result=unittest.TextTestRunner(verbosity=2).run(suite)
(ROOT/'results.json').write_text(json.dumps(dict(tests=result.testsRun,failures=len(result.failures),errors=len(result.errors)),indent=2)+'\n')
raise SystemExit(not result.wasSuccessful())
