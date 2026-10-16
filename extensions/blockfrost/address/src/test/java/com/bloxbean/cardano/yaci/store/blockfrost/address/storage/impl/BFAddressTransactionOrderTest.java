package com.bloxbean.cardano.yaci.store.blockfrost.address.storage.impl;

import com.bloxbean.cardano.yaci.store.common.model.Order;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BFAddressTransactionOrderTest {
    @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void transactionPositionAndInclusiveRangeAreAppliedBeforePageSelection() throws Exception {
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            d.execute("create table transaction(tx_hash text,tx_index int,block bigint,block_time bigint)");
            d.execute("create table address_utxo(tx_hash text,output_index smallint,owner_addr text,owner_addr_full text,block bigint,block_time bigint,slot bigint)");
            d.execute("create table tx_input(tx_hash text,output_index smallint,spent_tx_hash text,spent_at_block bigint,spent_block_time bigint,spent_at_slot bigint)");
            d.execute("insert into transaction values('z',0,100,1000),('a',1,100,1000),('m',2,100,1000),('spent',0,101,1010)");
            d.execute("insert into address_utxo values('z',0,'addr1qy',null,100,1000,200),('z',1,'addr1qy',null,100,1000,200),('a',0,'addr1qy',null,100,1000,200),('m',0,'addr1qy',null,100,1000,200)");
            d.execute("insert into tx_input values('z',0,'spent',101,1010,210)");
            var r=new BFAddressStorageReaderImpl(d);
            assertThat(r.findAddressTransactions("addr1qy",0,2,Order.asc,null,null)).extracting(x->x.getTxHash()).containsExactly("z","a");
            assertThat(r.findAddressTransactions("addr1qy",1,2,Order.asc,null,null)).extracting(x->x.getTxHash()).containsExactly("m","spent");
            assertThat(r.findAddressTransactions("addr1qy",0,2,Order.desc,null,null)).extracting(x->x.getTxHash()).containsExactly("spent","m");
            assertThat(r.findAddressTransactions("addr1qy",0,1,Order.asc,"100:1","100:2")).extracting(x->x.getTxHash()).containsExactly("a");
            assertThat(r.findAddressTransactions("addr1qy",1,1,Order.asc,"100:1","100:2")).extracting(x->x.getTxHash()).containsExactly("m");
        }
    }
}
