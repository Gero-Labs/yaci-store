#!/usr/bin/env python3
"""Offline manifest and guarded retained-byte repair. psql uses existing PG* environment."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from raw_cbor import datum, witness_datums, Invalid

SCHEMA = 'bfcompat_datum_repair_20261015'
HEX64 = re.compile(r'[0-9a-f]{64}')

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)

def fingerprint(row):
    return hashlib.sha256(canonical(row).encode()).hexdigest()

def durable_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)

def literal(value):
    if value is None:
        return 'NULL'
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"

def query(sql, write=False):
    env = dict(os.environ)
    env['PGAPPNAME'] = 'yaci-historical-datum-repair'
    env['PGOPTIONS'] = '-c statement_timeout=5000 -c lock_timeout=250 -c jit=off'
    if not write:
        env['PGOPTIONS'] += ' -c default_transaction_read_only=on'
    result = subprocess.run(['psql', '-X', '-At', '-v', 'ON_ERROR_STOP=1'], input=sql,
                            text=True, capture_output=True, env=env, timeout=15)
    if result.returncode:
        raise RuntimeError('Database operation failed; inspect PostgreSQL server diagnostics')
    return [json.loads(line) for line in result.stdout.splitlines() if line.startswith(('{', '['))]

def select_row(hash_):
    return query("SELECT row_to_json(d) FROM yaci_store.datum d WHERE hash=" + literal(hash_))

def row_sql(hash_):
    return 'SELECT * FROM yaci_store.datum WHERE hash=' + literal(hash_) + ' FOR UPDATE'

def validate_entry(entry):
    if not HEX64.fullmatch(entry['before']['hash']) or not HEX64.fullmatch(entry['raw_hash']):
        raise ValueError('Invalid datum key')
    if entry['before_fingerprint'] != fingerprint(entry['before']):
        raise ValueError('Precondition fingerprint mismatch')
    if datum(entry['before']['datum']) != entry['raw_hash']:
        raise ValueError('Raw hash mismatch')

def provenance(row):
    tx = row.get('created_at_tx')
    if not isinstance(tx, str) or not HEX64.fullmatch(tx):
        return None, 'missing_source_transaction'
    transactions = query('SELECT row_to_json(t) FROM (SELECT tx_hash,slot,tx_index FROM yaci_store.transaction WHERE tx_hash=' + literal(tx) + ')t')
    if len(transactions) != 1 or transactions[0]['slot'] is None:
        return None, 'source_transaction_unavailable'
    chain = transactions[0]
    outputs = query('SELECT row_to_json(o) FROM (SELECT tx_hash,slot,output_index,inline_datum FROM yaci_store.address_utxo WHERE tx_hash=' + literal(tx) + ' AND slot=' + literal(chain['slot']) + ' AND inline_datum IS NOT NULL ORDER BY output_index LIMIT 1001)o')
    if len(outputs) > 1000:
        return None, 'occurrence_budget_exceeded'
    for output in outputs:
        if output['inline_datum'] == row['datum']:
            return dict(kind='inline', tx_hash=tx, slot=chain['slot'], tx_index=chain['tx_index'],
                        output_index=output['output_index'], cbor=row['datum']), None
    raw, source_debt = read_source('SELECT row_to_json(c) FROM (SELECT slot,encode(cbor_data,\'hex\') AS cbor FROM yaci_store.transaction_cbor WHERE tx_hash=' + literal(tx) + ')c', 'source_transaction_cbor_unavailable')
    if len(raw) == 1 and raw[0]['slot'] == chain['slot']:
        try:
            for witness in witness_datums(raw[0]['cbor'], tx):
                if witness['cbor'] == row['datum']:
                    return dict(kind='witness', tx_hash=tx, slot=chain['slot'], tx_index=chain['tx_index'],
                                witness_index=witness['index'], cbor=row['datum'],
                                tx_cbor=raw[0]['cbor'], tx_cbor_sha256=hashlib.sha256(bytes.fromhex(raw[0]['cbor'])).hexdigest()), None
        except Invalid:
            return None, 'source_transaction_cbor_invalid'
    return None, source_debt or 'no_exact_occurrence_in_source_transaction'

def read_source(query_sql, fallback):
    # An optional source table may be disabled on this indexer. Record explicit debt;
    # never turn lack of source bytes into a fabricated occurrence.
    try:
        return query(query_sql), None
    except RuntimeError:
        return [], fallback


def discover(options):
    destination = options.directory.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    state_file = destination / 'cursor.json'
    state = json.loads(state_file.read_text()) if state_file.exists() else {'network': options.network, 'cursor': '', 'batch': 0}
    if state['network'] != options.network:
        raise ValueError('Manifest network changed')
    rows = query('SELECT row_to_json(d) FROM (SELECT * FROM yaci_store.datum WHERE hash>' + literal(state['cursor']) + ' ORDER BY hash LIMIT ' + str(options.count) + ')d')
    entries = []
    for row in rows:
        entry = {'network': options.network, 'before': row, 'before_fingerprint': fingerprint(row)}
        try:
            entry['raw_hash'] = datum(row['datum'])
            existing = select_row(entry['raw_hash'])
            entry['existing_raw'] = existing[0] if existing else None
            if existing and existing[0]['datum'] != row['datum']:
                entry['classification'] = 'conflict'
            elif entry['raw_hash'] != row['hash']:
                entry['classification'] = 'already_repaired' if existing else 'retained_wrong_key'
            elif row.get('slot') is None:
                entry['classification'] = 'missing_provenance'
            else:
                entry['classification'] = 'correct'
            if row.get('slot') is None:
                entry['provenance'], entry['debt'] = provenance(row)
        except (Invalid, KeyError, TypeError):
            entry['classification'] = 'invalid_retained_payload'
        entries.append(entry)
    batch = {'format': 1, 'network': options.network, 'cursor_before': state['cursor'],
             'cursor_after': rows[-1]['hash'] if rows else state['cursor'], 'entries': entries}
    digest = fingerprint(batch)
    path = destination / f"batch-{state['batch']:06d}-{digest}.json"
    if path.exists() and path.read_text() != canonical(batch) + '\n':
        raise ValueError('Existing manifest differs')
    path.write_text(canonical(batch) + '\n')
    # Cursor is advanced only after the immutable batch file is durable. A crash before
    # replace repeats one batch; the content-addressed filename makes that harmless.
    with path.open('rb') as stream:
        os.fsync(stream.fileno())
    durable_directory(destination)
    state.update(cursor=batch['cursor_after'], batch=state['batch'] + 1, complete=not rows)
    tmp = state_file.with_suffix('.tmp')
    tmp.write_text(canonical(state) + '\n')
    with tmp.open('rb') as stream:
        os.fsync(stream.fileno())
    tmp.replace(state_file)
    durable_directory(destination)
    print(canonical({'manifest': str(path), 'sha256': digest, 'rows': len(rows), 'complete': not rows}))

def proof_sql(proof, row):
    if not proof:
        return 'false'
    if proof.get('tx_hash') != row.get('created_at_tx') or proof.get('cbor') != row['datum']:
        raise ValueError('Provenance does not match retained source')
    tx, slot = literal(proof['tx_hash']), literal(proof['slot'])
    tx_guard = 'EXISTS(SELECT 1 FROM yaci_store.transaction WHERE tx_hash=' + tx + ' AND slot=' + slot + ' AND tx_index IS NOT DISTINCT FROM ' + literal(proof['tx_index']) + ')'
    if proof['kind'] == 'inline':
        return tx_guard + ' AND EXISTS(SELECT 1 FROM yaci_store.address_utxo WHERE tx_hash=' + tx + ' AND slot=' + slot + ' AND output_index=' + literal(proof['output_index']) + ' AND inline_datum=' + literal(row['datum']) + ')'
    if proof['kind'] == 'witness':
        witnesses = witness_datums(proof['tx_cbor'], proof['tx_hash'])
        if not any(v['index'] == proof['witness_index'] and v['cbor'] == row['datum'] for v in witnesses):
            raise ValueError('Witness provenance absent')
        return tx_guard + ' AND EXISTS(SELECT 1 FROM yaci_store.transaction_cbor WHERE tx_hash=' + tx + ' AND slot=' + slot + ' AND cbor_data=decode(' + literal(proof['tx_cbor']) + ",'hex'))"
    raise ValueError('Unknown proof kind')

def apply(options):
    raw = options.manifest.read_text()
    batch = json.loads(raw)
    if batch.get('format') != 1:
        raise ValueError('Unsupported repair manifest format')
    digest = fingerprint(batch)
    if digest != options.sha256 or batch['network'] != options.network:
        raise ValueError('Reviewed manifest digest/network mismatch')
    eligible = [e for e in batch['entries'] if e['classification'] == 'retained_wrong_key'
                or (e['classification'] == 'missing_provenance' and e.get('provenance'))]
    if len({e['before']['hash'] for e in eligible}) != len(eligible):
        raise ValueError('Duplicate manifest entry key')
    if sum(len(e['before']['datum']) + len((e.get('provenance') or {}).get('tx_cbor','')) for e in eligible) > 8_388_608:
        raise ValueError('Apply byte budget exceeded')
    if len(eligible) > 100:
        raise ValueError('Apply batch exceeds100 entries')
    for entry in eligible:
        validate_entry(entry)
    # Only explicit apply reaches this write path. Legacy aliases remain untouched,
    # aside from a null-slot CAS proven by exact occurrence in created_at_tx.
    sql = ['BEGIN;', "SET LOCAL statement_timeout='5s'; SET LOCAL lock_timeout='250ms';",
           'CREATE SCHEMA IF NOT EXISTS ' + SCHEMA + ';',
           f'''CREATE TABLE IF NOT EXISTS {SCHEMA}.journal(
             manifest_sha256 text NOT NULL,entry_key text NOT NULL,before_row jsonb NOT NULL,
             after_row jsonb NOT NULL,raw_hash text NOT NULL,inserted boolean NOT NULL,
             old_slot bigint,new_slot bigint,legacy_after jsonb NOT NULL,recorded_at timestamptz NOT NULL DEFAULT now(),
             PRIMARY KEY(manifest_sha256,entry_key));''']
    # Manifest apply takes a source SHARE lock before row locks to freeze provenance
    # inserts/updates/deletes. Locks are bounded; use only a reviewed maintenance window.
    proofs = [e['provenance'] for e in eligible if e.get('provenance') and e['before'].get('slot') is None]
    if proofs:
        tables = ['yaci_store.transaction']
        if any(p['kind'] == 'inline' for p in proofs):
            tables += ['yaci_store.address_utxo']
        if any(p['kind'] == 'witness' for p in proofs):
            tables += ['yaci_store.transaction_cbor']
        sql += ['LOCK TABLE ' + ','.join(sorted(tables)) + ' IN SHARE MODE;']
    keys = sorted({key for e in eligible for key in (e['before']['hash'], e['raw_hash'])})
    if keys:
        sql += ["DO $datum_locks$ DECLARE lock_id bigint; BEGIN\n"
                "FOR lock_id IN SELECT DISTINCT hashtext('datum-repair:'||v) AS id FROM unnest(ARRAY["
                + ','.join(literal(key) for key in keys)
                + "])v ORDER BY id LOOP\nPERFORM pg_advisory_xact_lock(lock_id);\nEND LOOP;\nEND $datum_locks$;"]
    for entry in sorted(eligible, key=lambda e: e['before']['hash']):
        row, target, proof = entry['before'], entry['raw_hash'], entry.get('provenance')
        old = row['hash']
        candidate_slot = proof['slot'] if row.get('slot') is None and proof else row.get('slot')
        old_json = literal(canonical(row)) + '::jsonb'
        sql += [f"DO $entry$ DECLARE source jsonb; current_target jsonb; inserted boolean:=false; changed bigint; after_row jsonb; legacy_after jsonb; prior record; BEGIN\n"
                f"SELECT * INTO prior FROM {SCHEMA}.journal WHERE manifest_sha256={literal(digest)} AND entry_key={literal(old)};\n"
                f"IF FOUND THEN\nSELECT to_jsonb(d) INTO source FROM ({row_sql(old)})d;\nSELECT to_jsonb(d) INTO current_target FROM ({row_sql(target)})d;\n"
                f"IF source IS DISTINCT FROM prior.legacy_after OR current_target IS DISTINCT FROM prior.after_row THEN RAISE EXCEPTION 'Journal target changed'; END IF;\nRETURN; END IF;\n"
                f"SELECT to_jsonb(d) INTO source FROM ({row_sql(old)})d;\n"
                f"IF source IS DISTINCT FROM {old_json} THEN RAISE EXCEPTION 'Retained row changed'; END IF;\n"
                + (f"IF NOT ({proof_sql(proof,row)}) THEN RAISE EXCEPTION 'Provenance source changed'; END IF;\n" if proof and row.get('slot') is None else '')
                + f"SELECT to_jsonb(d) INTO current_target FROM ({row_sql(target)})d;\n"
                f"IF current_target IS NOT NULL AND current_target->>'datum' IS DISTINCT FROM source->>'datum' THEN RAISE EXCEPTION 'Raw key byte conflict'; END IF;\n"
                f"IF current_target IS NULL THEN\n"
                f"INSERT INTO yaci_store.datum(hash,datum,created_at_tx,slot,create_datetime,update_datetime) VALUES({literal(target)},{literal(row['datum'])},{literal(row.get('created_at_tx'))},{literal(candidate_slot)},{literal(row.get('create_datetime'))}::timestamp,{literal(row.get('update_datetime'))}::timestamp) ON CONFLICT(hash) DO NOTHING;\n"
                f"GET DIAGNOSTICS changed = ROW_COUNT; inserted:=changed=1;\n"
                f"END IF;\n"
                f"SELECT to_jsonb(d) INTO current_target FROM ({row_sql(target)})d;\n"
                f"IF current_target->>'datum' IS DISTINCT FROM source->>'datum' THEN RAISE EXCEPTION 'Concurrent raw key conflict'; END IF;\n"
                + (f"UPDATE yaci_store.datum SET slot={literal(candidate_slot)} WHERE hash={literal(old)} AND slot IS NULL AND datum={literal(row['datum'])} AND created_at_tx IS NOT DISTINCT FROM {literal(row.get('created_at_tx'))};\n" if proof and row.get('slot') is None else '')
                + f"SELECT to_jsonb(d) INTO after_row FROM yaci_store.datum d WHERE hash={literal(target)};\n"
                f"SELECT to_jsonb(d) INTO legacy_after FROM yaci_store.datum d WHERE hash={literal(old)};\n"
                f"INSERT INTO {SCHEMA}.journal(manifest_sha256,entry_key,before_row,after_row,raw_hash,inserted,old_slot,new_slot,legacy_after) VALUES({literal(digest)},{literal(old)},source,after_row,{literal(target)},inserted,{literal(row.get('slot'))},{literal(candidate_slot)},legacy_after);\nEND $entry$;"]
    sql += ['COMMIT;']
    if options.sql_output:
        options.sql_output.write_text('\n'.join(sql) + '\n')
    else:
        query('\n'.join(sql), write=True)
    print(canonical({'manifest_sha256': digest, 'eligible': len(eligible), 'mode': 'sql' if options.sql_output else 'applied'}))

def rollback(options):
    # Rollback preserves legacy aliases and uses the immutable journal. An inserted
    # alias is removed only when unchanged and no current output/script reference
    # points to it; reference table locks prevent an insert racing that check.
    if not HEX64.fullmatch(options.sha256):
        raise ValueError('Invalid journal digest')
    digest = literal(options.sha256)
    sql = f"""BEGIN;
SET LOCAL statement_timeout='5s'; SET LOCAL lock_timeout='250ms';
DO $references$
DECLARE reference record;
BEGIN
 FOR reference IN SELECT c.relname,a.attname FROM pg_class c
 JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
 WHERE n.nspname='yaci_store' AND c.relkind IN('r','p') AND a.attnum>0 AND NOT a.attisdropped
 AND a.attname IN('data_hash','datum_hash') ORDER BY c.relname,a.attname LOOP
  IF (reference.relname,reference.attname) NOT IN(
   ('address_utxo','data_hash'),('address_utxo_unspent','data_hash'),
   ('address_utxo_current','data_hash'),('transaction_scripts','datum_hash'))
   AND NOT EXISTS(SELECT 1 FROM pg_inherits i JOIN pg_class parent ON parent.oid=i.inhparent
    JOIN pg_namespace pn ON pn.oid=parent.relnamespace JOIN pg_class child ON child.oid=i.inhrelid
    WHERE child.relname=reference.relname AND pn.nspname='yaci_store'
    AND parent.relname IN('address_utxo','address_utxo_unspent','address_utxo_current','transaction_scripts')) THEN
   RAISE EXCEPTION 'Unknown datum reference table: %',reference.relname;
  END IF;
 END LOOP;
 FOR reference IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='yaci_store' AND c.relkind IN('r','p')
 AND c.relname IN('address_utxo','address_utxo_unspent','address_utxo_current','transaction_scripts') ORDER BY c.relname LOOP
  EXECUTE format('LOCK TABLE yaci_store.%I IN SHARE MODE',reference.relname);
 END LOOP;
END $references$;
CREATE TABLE IF NOT EXISTS {SCHEMA}.rollback_journal(
 manifest_sha256 text NOT NULL,entry_key text NOT NULL,rolled_back_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(manifest_sha256,entry_key));
DO $rollback$
DECLARE item record; original jsonb; target jsonb; reference record; referenced boolean; lock_id bigint;
BEGIN
 FOR lock_id IN SELECT DISTINCT hashtext('datum-repair:'||v) AS id FROM
  (SELECT entry_key AS v FROM {SCHEMA}.journal WHERE manifest_sha256={digest}
   UNION SELECT raw_hash FROM {SCHEMA}.journal WHERE manifest_sha256={digest}) keys ORDER BY id LOOP
  PERFORM pg_advisory_xact_lock(lock_id);
 END LOOP;
 FOR item IN SELECT * FROM {SCHEMA}.journal WHERE manifest_sha256={digest} ORDER BY entry_key LOOP
  IF EXISTS(SELECT 1 FROM {SCHEMA}.rollback_journal WHERE manifest_sha256=item.manifest_sha256 AND entry_key=item.entry_key) THEN CONTINUE; END IF;
  SELECT to_jsonb(d) INTO original FROM yaci_store.datum d WHERE hash=item.entry_key FOR UPDATE;
  SELECT to_jsonb(d) INTO target FROM yaci_store.datum d WHERE hash=item.raw_hash FOR UPDATE;
  IF original IS DISTINCT FROM item.legacy_after OR target IS DISTINCT FROM item.after_row THEN RAISE EXCEPTION 'Repair row changed after journal'; END IF;
  IF item.inserted THEN
   FOR reference IN SELECT c.relname,a.attname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname='yaci_store' AND c.relkind IN('r','p')
    AND a.attnum>0 AND NOT a.attisdropped AND
    ((c.relname IN('address_utxo','address_utxo_unspent','address_utxo_current') AND a.attname='data_hash')
     OR (c.relname='transaction_scripts' AND a.attname='datum_hash')) ORDER BY c.relname LOOP
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM yaci_store.%I WHERE %I=$1)',reference.relname,reference.attname) INTO referenced USING item.raw_hash;
    IF referenced THEN RAISE EXCEPTION 'Repair alias acquired a reference'; END IF;
   END LOOP;
   DELETE FROM yaci_store.datum WHERE hash=item.raw_hash;
  END IF;
  IF item.old_slot IS DISTINCT FROM item.new_slot THEN
   UPDATE yaci_store.datum SET slot=item.old_slot WHERE hash=item.entry_key AND slot IS NOT DISTINCT FROM item.new_slot;
  END IF;
  INSERT INTO {SCHEMA}.rollback_journal(manifest_sha256,entry_key) VALUES(item.manifest_sha256,item.entry_key);
 END LOOP;
END $rollback$;
COMMIT;
"""
    if options.sql_output:
        options.sql_output.write_text(sql)
    else:
        query(sql, write=True)
    print(canonical({'manifest_sha256': options.sha256, 'mode': 'rollback_sql' if options.sql_output else 'rolled_back'}))

def main():
    p = argparse.ArgumentParser(description=__doc__)
    commands = p.add_subparsers(dest='command', required=True)
    d = commands.add_parser('discover')
    d.add_argument('--network', required=True, choices=['mainnet','preprod','preview'])
    d.add_argument('--directory', required=True, type=Path)
    d.add_argument('--count', type=int, default=25, choices=range(1,101))
    a = commands.add_parser('apply')
    a.add_argument('--network', required=True, choices=['mainnet','preprod','preview'])
    a.add_argument('--manifest', required=True, type=Path)
    a.add_argument('--sha256', required=True)
    a.add_argument('--sql-output', type=Path, help='Prepare reviewable SQL instead of applying it')
    r = commands.add_parser('rollback')
    r.add_argument('--sha256', required=True)
    r.add_argument('--sql-output', type=Path)
    options = p.parse_args()
    try:
        {'discover': discover, 'apply': apply, 'rollback': rollback}[options.command](options)
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        raise SystemExit(1)

if __name__ == '__main__':
    main()
