package com.bloxbean.cardano.yaci.store.blockfrost.pools.storage.impl;

import com.bloxbean.cardano.yaci.store.common.util.PoolUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BFPoolListQueriesTest {
    @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
    private static String pool(int n) { return String.format("%056x",n); }
    @BeforeEach void setup() throws Exception {
        try(Connection c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            d.execute("drop schema public cascade; create schema public");
            d.execute("create table epoch(number bigint); insert into epoch values(10)");
            d.execute("create table block(epoch int); insert into block values(10)");
            d.execute("create table pool(pool_id text,status text,retire_epoch int,slot bigint,tx_hash text,tx_index int,cert_index int,registration_slot bigint,epoch int)");
            d.execute("create table pool_registration(pool_id text,slot bigint,tx_index int,cert_index int)");
            d.execute("create table pool_retirement(pool_id text,slot bigint,tx_index int,cert_index int,retirement_epoch int)");
            // First appearances in the same slot must follow tx/certificate position, not hash.
            reg(d,1,100,0,0); reg(d,2,100,0,1); reg(d,3,100,1,0); reg(d,4,110,0,0);
            reg(d,5,120,0,0); reg(d,6,130,0,0); reg(d,7,140,0,0);
            reg(d,8,150,0,0); retire(d,8,150,0,1,12); // BF excludes same-tx future retirement
            // Updating1 must not move its global first-appearance position.
            reg(d,1,1000,0,0);
            retire(d,2,200,1,0,12); // future retirement remains in /pools
            retire(d,3,200,2,0,10); // effective retirement is absent
            retire(d,4,210,0,0,10); reg(d,4,210,0,1); // later same-tx registration cancels
            retire(d,5,300,0,1,10);
            retire(d,6,300,1,0,10); // same slot; tx-index order disagrees with hash order
            retire(d,7,400,0,0,8); reg(d,7,410,0,0); // re-registration cancels old retirement
            // Materialized native pool state included only to exercise the original regression.
            state(d,1,"UPDATE",1000,0,0,1000,null,"f");
            state(d,2,"RETIRING",200,1,0,100,12,"b");
            state(d,3,"RETIRED",201,0,0,100,10,"a");
            state(d,4,"UPDATE",210,0,1,210,null,"c");
            state(d,5,"RETIRING",300,0,1,120,10,"a"); state(d,5,"RETIRED",310,0,0,120,10,"a");
            state(d,6,"RETIRING",300,1,0,130,10,"f"); state(d,6,"RETIRED",310,1,0,130,10,"f");
            state(d,7,"UPDATE",410,0,0,410,null,"d");
        }
    }
    private void reg(org.jooq.DSLContext d,int p,long slot,int tx,int cert) {
        d.execute("insert into pool_registration values(?,?,?,?)",pool(p),slot,tx,cert);
    }
    private void retire(org.jooq.DSLContext d,int p,long slot,int tx,int cert,int epoch) {
        d.execute("insert into pool_retirement values(?,?,?,?,?)",pool(p),slot,tx,cert,epoch);
    }
    private void state(org.jooq.DSLContext d,int p,String status,long slot,int tx,int cert,long registered,Integer epoch,String hash) {
        d.execute("insert into pool values(?,?,?,?,?,?,?,?,?)",pool(p),status,epoch,slot,hash,tx,cert,registered,10);
    }
    @Test void activePoolsUseFirstAppearanceBeforeFilteringAndPaging() throws Exception {
        try(Connection c=connection()) {
            var r=new BFPoolsStorageReaderImpl(DSL.using(c,SQLDialect.POSTGRES),new ObjectMapper());
            assertThat(r.getPoolIds(0,100,"asc")).containsExactlyElementsOf(bech32(1,2,4,7));
            assertThat(r.getPoolIds(0,2,"asc")).containsExactlyElementsOf(bech32(1,2));
            assertThat(r.getPoolIds(1,2,"asc")).containsExactlyElementsOf(bech32(4,7));
            assertThat(r.getPoolIds(0,100,"desc")).containsExactlyElementsOf(bech32(7,4,2,1));
            assertThat(r.getPoolIds(2,2,"desc")).isEmpty();
        }
    }
    @Test void retireListsUseAnnouncementTransactionPositionAndCurrentEffectiveState() throws Exception {
        try(Connection c=connection()) {
            var r=new BFPoolsStorageReaderImpl(DSL.using(c,SQLDialect.POSTGRES),new ObjectMapper());
            assertThat(r.getRetiredPools(0,100,"asc")).extracting(x->x.poolId()).containsExactly(pool(3),pool(5),pool(6));
            assertThat(r.getRetiredPools(0,100,"desc")).extracting(x->x.poolId()).containsExactly(pool(6),pool(5),pool(3));
            assertThat(r.getRetiredPools(1,1,"desc")).extracting(x->x.poolId()).containsExactly(pool(5));
            assertThat(r.getRetiringPools(0,100,"asc")).extracting(x->x.poolId()).containsExactly(pool(8),pool(2));
        }
    }
    private List<String> bech32(int... ids) {return java.util.Arrays.stream(ids).mapToObj(i->PoolUtil.getBech32PoolId(pool(i))).toList();}
    private Connection connection() throws Exception {return DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());}
}
