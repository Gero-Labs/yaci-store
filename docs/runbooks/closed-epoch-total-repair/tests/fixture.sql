
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
INSERT INTO yaci_store.block (hash,number,slot,epoch,block_time,prev_hash,no_of_txs,total_fees,total_output) VALUES
('2711a313df7a8ebd2a7316f3bfb38158e0d93ff250e54d441695052ecb6da27f',100,1000,633,10000,'3fb7eaa98f43ec52c95bae9989e0cf44dc91aba5ff87cc0c4d24516f7e8c5e70',1,200000,10000000),
('3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca',101,1020,633,10020,'2711a313df7a8ebd2a7316f3bfb38158e0d93ff250e54d441695052ecb6da27f',2,400000,105000000),
('3d6e1011cd20f1acafe63d15c9b1570a1e9a445313c4eecad30e33e43a6b3711',102,1040,633,10040,'3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca',1,200000,100000000),
('384850f53015ccd428d0facc7a2f633fa95f4d0be6b22a0113a0b4a7d1575887',103,1060,633,10060,'3d6e1011cd20f1acafe63d15c9b1570a1e9a445313c4eecad30e33e43a6b3711',1,2000000,1000000),
('6326e05815db8747018aae66e3c300e0740d7f210cde6b9c70fb9de0233a346a',104,1080,633,10080,'384850f53015ccd428d0facc7a2f633fa95f4d0be6b22a0113a0b4a7d1575887',0,0,0),
('97380187a878903ffe722b7bfd8d8ba92457ef77de0216cfe9261c72c2b87397',1000,20000,634,29000,'a8d670bb03d321da16cbc92e208d76926e381239b83695f3ba772f6e0abcd07f',0,0,0);
INSERT INTO yaci_store.transaction (tx_hash,block_hash,block,slot,epoch,block_time,tx_index,invalid,fee,outputs,collateral_return_json,collateral_inputs,total_collateral) VALUES
('0a29baef62af5d005f23e37194cd201a2c082d65e8ce0b9428edd994fcea320b','2711a313df7a8ebd2a7316f3bfb38158e0d93ff250e54d441695052ecb6da27f',100,1000,633,10000,0,FALSE,200000,'[{"tx_hash": "0a29baef62af5d005f23e37194cd201a2c082d65e8ce0b9428edd994fcea320b", "output_index": 0}]'::jsonb,NULL,'[]'::jsonb,NULL),
('8929261486a5f3ade246b047f1307c892f88f77bdae7c9799dfe22af24f7cbf2','3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca',101,1020,633,10020,0,TRUE,3000000,'[{"tx_hash": "8929261486a5f3ade246b047f1307c892f88f77bdae7c9799dfe22af24f7cbf2", "output_index": 0}]'::jsonb,'{"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 7000000}]}'::jsonb,'[{"tx_hash": "0733ffdc4e59c749648daba419527bcfa170fa74d4fd035a8c76f545ce25a2e6", "output_index": 0}]'::jsonb,3000000),
('93e9f94d806a7b4933d8b88e06a3f6aa712821d276afb3acddc0d58ef1ec8f6e','3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca',101,1020,633,10020,1,FALSE,200000,'[{"tx_hash": "93e9f94d806a7b4933d8b88e06a3f6aa712821d276afb3acddc0d58ef1ec8f6e", "output_index": 0}]'::jsonb,NULL,'[]'::jsonb,NULL),
('779b55c9da2649009cb4a7281864bdc8757ce461eac5f595e1309dbfb1c13af6','3d6e1011cd20f1acafe63d15c9b1570a1e9a445313c4eecad30e33e43a6b3711',102,1040,633,10040,0,TRUE,8000000,'[{"tx_hash": "779b55c9da2649009cb4a7281864bdc8757ce461eac5f595e1309dbfb1c13af6", "output_index": 0}]'::jsonb,NULL,'[{"tx_hash": "41c0f92b6cc6ede09a2e894dbb2a874095ff375362dd5d51fb0f1046840b97b9", "output_index": 0}]'::jsonb,NULL),
('3d9085fce803aa2d6bf3a8b49d3d79b7584e8f91e47f42b947f40f8438d89b15','384850f53015ccd428d0facc7a2f633fa95f4d0be6b22a0113a0b4a7d1575887',103,1060,633,10060,0,TRUE,2000000,'[{"tx_hash": "3d9085fce803aa2d6bf3a8b49d3d79b7584e8f91e47f42b947f40f8438d89b15", "output_index": 0}]'::jsonb,'{"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 1000000}]}'::jsonb,'[{"tx_hash": "ca5861ddfc3fbba9608a105d774b2a1cfe22b68cfb240cb7b4a9a17d7f148ed2", "output_index": 0}]'::jsonb,2000000);
INSERT INTO yaci_store.address_utxo (tx_hash,output_index,block_hash,block,slot,epoch,block_time,lovelace_amount,is_collateral_return) VALUES
('0a29baef62af5d005f23e37194cd201a2c082d65e8ce0b9428edd994fcea320b',0,'2711a313df7a8ebd2a7316f3bfb38158e0d93ff250e54d441695052ecb6da27f',100,1000,633,10000,10000000,FALSE),
('8929261486a5f3ade246b047f1307c892f88f77bdae7c9799dfe22af24f7cbf2',1,'3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca',101,1020,633,10020,7000000,TRUE),
('93e9f94d806a7b4933d8b88e06a3f6aa712821d276afb3acddc0d58ef1ec8f6e',0,'3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca',101,1020,633,10020,5000000,FALSE),
('3d9085fce803aa2d6bf3a8b49d3d79b7584e8f91e47f42b947f40f8438d89b15',1,'384850f53015ccd428d0facc7a2f633fa95f4d0be6b22a0113a0b4a7d1575887',103,1060,633,10060,1000000,TRUE),
('0733ffdc4e59c749648daba419527bcfa170fa74d4fd035a8c76f545ce25a2e6',0,'3fb7eaa98f43ec52c95bae9989e0cf44dc91aba5ff87cc0c4d24516f7e8c5e70',99,980,632,9980,10000000,FALSE),
('41c0f92b6cc6ede09a2e894dbb2a874095ff375362dd5d51fb0f1046840b97b9',0,'3fb7eaa98f43ec52c95bae9989e0cf44dc91aba5ff87cc0c4d24516f7e8c5e70',99,980,632,9980,8000000,FALSE),
('ca5861ddfc3fbba9608a105d774b2a1cfe22b68cfb240cb7b4a9a17d7f148ed2',0,'3fb7eaa98f43ec52c95bae9989e0cf44dc91aba5ff87cc0c4d24516f7e8c5e70',99,980,632,9980,3000000,FALSE);
INSERT INTO yaci_store.invalid_transaction (tx_hash,slot,block_hash,transaction) VALUES
('8929261486a5f3ade246b047f1307c892f88f77bdae7c9799dfe22af24f7cbf2',1020,'3894b455e17ad07d34b3682fdbeb09a9c2136b3be1ea904672e7de949858b6ca','{"txHash": "8929261486a5f3ade246b047f1307c892f88f77bdae7c9799dfe22af24f7cbf2", "invalid": true, "body": {"txHash": "8929261486a5f3ade246b047f1307c892f88f77bdae7c9799dfe22af24f7cbf2", "fee": 200000, "totalCollateral": 3000000, "outputs": [{"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 100000000}]}], "collateralReturn": {"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 7000000}]}}}'::jsonb),
('779b55c9da2649009cb4a7281864bdc8757ce461eac5f595e1309dbfb1c13af6',1040,'3d6e1011cd20f1acafe63d15c9b1570a1e9a445313c4eecad30e33e43a6b3711','{"txHash": "779b55c9da2649009cb4a7281864bdc8757ce461eac5f595e1309dbfb1c13af6", "invalid": true, "body": {"txHash": "779b55c9da2649009cb4a7281864bdc8757ce461eac5f595e1309dbfb1c13af6", "fee": 200000, "totalCollateral": null, "outputs": [{"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 100000000}]}], "collateralReturn": null}}'::jsonb),
('3d9085fce803aa2d6bf3a8b49d3d79b7584e8f91e47f42b947f40f8438d89b15',1060,'384850f53015ccd428d0facc7a2f633fa95f4d0be6b22a0113a0b4a7d1575887','{"txHash": "3d9085fce803aa2d6bf3a8b49d3d79b7584e8f91e47f42b947f40f8438d89b15", "invalid": true, "body": {"txHash": "3d9085fce803aa2d6bf3a8b49d3d79b7584e8f91e47f42b947f40f8438d89b15", "fee": 200000, "totalCollateral": 2000000, "outputs": [{"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 9000000}]}], "collateralReturn": {"address": "fixture-address", "amounts": [{"unit": "lovelace", "quantity": 1000000}]}}}'::jsonb);
INSERT INTO yaci_store.epoch (number,block_count,transaction_count,total_fees,total_output,start_time,end_time,max_slot) VALUES
(633,2,2,4174805,999999999,10060,10020,1020);
INSERT INTO public.block SELECT number,number,decode(hash,'hex'),slot,epoch,to_timestamp(block_time) FROM yaci_store.block;
INSERT INTO public.tx SELECT row_number() OVER (ORDER BY block,tx_index),block,tx_index,decode(tx_hash,'hex'),fee,(SELECT coalesce(sum(lovelace_amount),0) FROM yaci_store.address_utxo u WHERE u.tx_hash=t.tx_hash),NOT invalid FROM yaci_store.transaction t;
