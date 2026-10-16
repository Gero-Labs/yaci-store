package com.bloxbean.cardano.yaci.store.blockfrost.account.storage.impl;

import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreProperties;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BFAccountOutputOrderTest {
    @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void addressAndAssetPagesFollowOutputPositionBeforePagination() throws Exception {
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            d.execute("drop schema public cascade; create schema public");
            d.execute("create table address_utxo(tx_hash text,output_index smallint,owner_addr text,owner_addr_full text,owner_payment_credential text,owner_stake_addr text,slot bigint,tx_index int,amounts jsonb)");
            d.execute("create table tx_input(tx_hash text,output_index smallint)");
            d.execute("create table transaction(tx_hash text,invalid boolean,tx_index int)");
            d.execute("insert into transaction values('z',false,0),('a',false,1),('m',false,0),('invalid',true,0)");
            d.execute("insert into address_utxo values('z',0,'addr1qz',null,'cred-z','stake',10,null,'[{\"unit\":\"z\",\"quantity\":\"2\"},{\"unit\":\"b\",\"quantity\":\"3\"}]'),"
                + "('a',0,'addr1qa',null,'cred-a','stake',10,null,'[{\"unit\":\"a\",\"quantity\":\"5\"}]'),"
                + "('m',0,'addr1qm',null,'cred-z','stake',11,0,'[{\"unit\":\"b\",\"quantity\":\"7\"}]'),"
                + "('invalid',0,'addr1qi',null,'cred-i','stake',12,0,'[{\"unit\":\"x\",\"quantity\":\"99\"}]')");
            var props=new UtxoStoreProperties(); props.setSaveAddress(false);
            var r=new BFAccountStorageReaderImpl(d,props);
            assertThat(r.findAddresses("stake",0,2,Order.asc)).extracting(x->x.address()).containsExactly("addr1qz","addr1qa");
            // Invalid transactions persist only their collateral-return output in UtxoProcessor.
            // BF includes that output in its address list, but excludes it from valid-contract assets.
            // Same credential's later address does not create a duplicate logical address.
            assertThat(r.findAddresses("stake",0,100,Order.desc)).extracting(x->x.address()).containsExactly("addr1qi","addr1qa","addr1qz");
            assertThat(r.findAddressAssets("stake",0,100,Order.asc)).extracting(x->x.unit()).containsExactly("b","z","a");
            assertThat(r.findAddressAssets("stake",0,100,Order.desc)).extracting(x->x.unit()).containsExactly("b","a","z");
            assertThat(r.findAddressAssets("stake",1,1,Order.desc)).extracting(x->x.unit()).containsExactly("a");
            assertThat(r.findAddressAssets("stake",0,1,Order.desc).getFirst().quantity().toString()).isEqualTo("10");
        }
    }
    @Test void samePaymentCredentialKeepsKeyAndScriptRepresentativesSeparate() throws Exception {
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            d.execute("drop schema public cascade; create schema public");
            d.execute("create table address_utxo(tx_hash text,output_index smallint,owner_addr text,owner_addr_full text,owner_payment_credential text,owner_stake_addr text,slot bigint,tx_index int,amounts jsonb)");
            d.execute("create table transaction(tx_hash text,tx_index int)");
            d.execute("insert into address_utxo values('key',0,'addr1qkey',null,'same','stake',10,0,'[]'),('script',0,'addr1zscript',null,'same','stake',11,0,'[]'),('later-script',0,'addr1xlater',null,'same','stake',12,0,'[]')");
            var r=new BFAccountStorageReaderImpl(d,new UtxoStoreProperties());
            assertThat(r.findAddresses("stake",0,100,Order.asc)).extracting(x->x.address()).containsExactly("addr1qkey","addr1zscript");
            assertThat(r.findAddresses("stake",0,100,Order.desc)).extracting(x->x.address()).containsExactly("addr1zscript","addr1qkey");
        }
    }

}
