#!/usr/bin/env python3
"""Bounded original output/witness byte inventory; discovery only, no writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import repair
from raw_cbor import datum,witness_datums,Invalid

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',required=True,choices=['output','witness'])
    p.add_argument('--network',required=True,choices=['mainnet','preprod','preview'])
    p.add_argument('--directory',required=True,type=Path)
    p.add_argument('--count',type=int,default=25,choices=range(1,101))
    args=p.parse_args();args.directory.mkdir(parents=True,exist_ok=True)
    cursor_file=args.directory/'cursor.json'
    state=json.loads(cursor_file.read_text()) if cursor_file.exists() else dict(network=args.network,source=args.source,hash='',index=-1,batch=0)
    if state['network']!=args.network or state['source']!=args.source:raise ValueError('Inventory scope changed')
    if args.source=='output':
        # Primary-key keyset uses output_index first on deployed address_utxo.
        sql="SELECT row_to_json(o) FROM (SELECT tx_hash,output_index,slot,CASE WHEN octet_length(inline_datum)<=2097152 THEN inline_datum END AS inline_datum,coalesce(octet_length(inline_datum)>2097152,false) AS oversized FROM yaci_store.address_utxo WHERE (output_index,tx_hash)>("+str(state['index'])+','+repair.literal(state['hash'])+") ORDER BY output_index,tx_hash LIMIT "+str(args.count)+')o'
    else:
        sql="SELECT row_to_json(c) FROM (SELECT tx_hash,slot,CASE WHEN octet_length(cbor_data)<=1048576 THEN encode(cbor_data,'hex') END AS cbor,octet_length(cbor_data)>1048576 AS oversized FROM yaci_store.transaction_cbor WHERE tx_hash>"+repair.literal(state['hash'])+' ORDER BY tx_hash LIMIT '+str(args.count)+')c'
    rows=repair.query(sql);entries=[]
    for row in rows:
        if row['oversized']:
            entries.append(dict(tx_hash=row['tx_hash'],slot=row['slot'],source=args.source,classification='source_byte_budget_exceeded'))
            continue
        try:
            values=[]
            if args.source=='output':
                if row['inline_datum'] is not None:values=[dict(index=row['output_index'],cbor=row['inline_datum'],raw_hash=datum(row['inline_datum']))]
            else:values=witness_datums(row['cbor'],row['tx_hash'])
            for value in values:
                existing=repair.select_row(value['raw_hash'])
                entries.append(dict(tx_hash=row['tx_hash'],slot=row['slot'],source=args.source,
                     position=value['index'],cbor=value['cbor'],raw_hash=value['raw_hash'],
                     classification='missing_raw_payload' if not existing else 'present' if existing[0]['datum']==value['cbor'] else 'conflict'))
        except Invalid:
            entries.append(dict(tx_hash=row['tx_hash'],slot=row['slot'],source=args.source,classification='invalid_source_cbor'))
    batch=dict(format=1,network=args.network,source=args.source,cursor_before=dict(hash=state['hash'],index=state['index']),rows_read=len(rows),entries=entries)
    digest=repair.fingerprint(batch);destination=args.directory/f"batch-{state['batch']:06d}-{digest}.json";destination.write_text(repair.canonical(batch)+'\n')
    if rows:
        state.update(hash=rows[-1]['tx_hash'],index=rows[-1].get('output_index',-1))
    with destination.open('rb') as stream:os.fsync(stream.fileno())
    repair.durable_directory(args.directory)
    state.update(batch=state['batch']+1,complete=not rows)
    temp=cursor_file.with_suffix('.tmp');temp.write_text(repair.canonical(state)+'\n')
    with temp.open('rb') as stream:os.fsync(stream.fileno())
    temp.replace(cursor_file)
    repair.durable_directory(args.directory)
    print(repair.canonical(dict(batch=str(destination),rows_read=len(rows),sha256=digest,complete=not rows)))

if __name__=='__main__':main()
