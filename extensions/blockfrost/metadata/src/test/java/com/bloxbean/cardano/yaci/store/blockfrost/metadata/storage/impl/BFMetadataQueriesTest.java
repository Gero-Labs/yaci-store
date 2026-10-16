package com.bloxbean.cardano.yaci.store.blockfrost.metadata.storage.impl;

import com.bloxbean.cardano.yaci.store.blockfrost.metadata.mapper.BFMetadataMapper;
import com.bloxbean.cardano.yaci.store.blockfrost.metadata.service.BFMetadataService;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.bloxbean.cardano.yaci.store.api.metadata.service.MetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
class BFMetadataQueriesTest {
    @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void jsonAndCborPagesUseTransactionPositionInsteadOfHashOrder() throws Exception {
        try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword())) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            d.execute("create table transaction_metadata(id text,tx_hash text,tx_index int,slot bigint,label text,body text,cbor text)");
            d.execute("create table transaction(tx_hash text,tx_index int)");
            d.execute("insert into transaction values('z',0),('a',1),('m',0)");
            d.execute("insert into transaction_metadata values('1','z',null,10,'674','{\"msg\":[\"first\"]}','a11902a2a1636d736781656669727374'),('2','a',null,10,'674','{\"msg\":[\"second\"]}','a11902a2a1636d736781667365636f6e64'),('3','m',0,11,'674','{\"msg\":[\"third\"]}','a11902a2a1636d736781657468697264')");
            var nativeService=mock(MetadataService.class);
            var mapper=new BFMetadataMapper(){};
            var service=new BFMetadataService(nativeService,new BFMetadataStorageReaderImpl(d),mapper);
            assertThat(service.getMetadataByLabel("674",0,2,Order.asc)).extracting(x->x.getTxHash()).containsExactly("z","a");
            assertThat(service.getMetadataByLabel("674",1,2,Order.asc)).extracting(x->x.getTxHash()).containsExactly("m");
            assertThat(service.getMetadataByLabel("674",0,2,Order.desc)).extracting(x->x.getTxHash()).containsExactly("m","a");
            assertThat(service.getMetadataCborByLabel("674",0,2,Order.desc)).extracting(x->x.getTxHash()).containsExactly("m","a");
            var serialized=new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(service.getMetadataByLabel("674",0,1,Order.asc)));
            assertThat(serialized.get(0).get("json_metadata").get("msg").get(0).asText()).isEqualTo("first");
            assertThat(service.getMetadataCborByLabel("674",0,1,Order.asc).getFirst().getCborMetadata())
                .isEqualTo("\\xa11902a2a1636d736781656669727374");
            verifyNoInteractions(nativeService);
        }
    }
}
