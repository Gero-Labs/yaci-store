package com.bloxbean.cardano.yaci.store.blockfrost.governance.storage.impl;

import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.blockfrost.governance.util.BFDRepIdentity;
import com.bloxbean.cardano.yaci.store.common.model.Order;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact hosted BF rules, including transaction-only membership and unclamped rewards. */
@Testcontainers
class BFDRepDelegatorParityTest {
    @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String HASH="11".repeat(28);
    private static final String ID=GovId.drepFromKeyHash(HexUtil.decodeHexString(HASH));
    private static final String OTHER=GovId.drepFromKeyHash(HexUtil.decodeHexString("22".repeat(28)));
    @BeforeEach void setup() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            d.execute("drop schema public cascade; create schema public");
            d.execute("create table block(number bigint,slot bigint,epoch int); insert into block values(100,1000,10),(101,1010,11)");
            d.execute("create table epoch_param(epoch int,params json); insert into epoch_param values(12,'{}')");
            d.execute("create table delegation_vote(address text,drep_id text,drep_hash text,drep_type text,slot bigint,tx_index int,cert_index int)");
            d.execute("create table drep_registration(drep_id text,drep_hash text,cred_type text,type text,slot bigint,tx_index int,cert_index int)");
            d.execute("create table stake_registration(address text,type text,slot bigint,tx_index int,cert_index int)");
            d.execute("create table stake_address_balance(address text,quantity numeric,slot bigint,epoch int)");
            d.execute("create table stake_address_balance_current(address text,quantity numeric)");
            d.execute("create table epoch_stake(address text,amount bigint,epoch int)");
            d.execute("create table adapot_jobs(epoch int,type text,status text)");
            d.execute("create table reward(address text,amount bigint,spendable_epoch int,earned_epoch int,type text)");
            d.execute("create table reward_rest(address text,amount bigint,spendable_epoch int)");
            d.execute("create table instant_reward(address text,amount bigint,spendable_epoch int)");
            d.execute("create table withdrawal(address text,amount bigint,epoch int)");
            d.execute("create table ss_max_slot_balances(address text,mark_epoch int)");
            d.execute("create table ss_pool_rewards(address text,withdrawable_reward numeric,mark_epoch int)");
            d.execute("create table ss_pool_refund_rewards(address text,pool_refund_withdrawable_reward numeric,mark_epoch int)");
            d.execute("create table ss_insta_spendable_rewards(address text,insta_withdrawable_reward numeric,mark_epoch int)");
            d.execute("create table ss_spendable_reward_rest(address text,withdrawable_reward_rest numeric,mark_epoch int)");
            d.execute("create table ss_last_withdrawal(address text,mark_epoch int)");
            d.execute("insert into drep_registration values(?,?,?,'REG_DREP_CERT',10,0,1)",ID,HASH,"ADDR_KEYHASH");
        }
    }
    private Connection connection() throws Exception {return DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());}
    private void reg(DSLContext d,String a,String type,long slot,int tx,int cert) {d.execute("insert into stake_registration values(?,?,?,?,?)",a,type,slot,tx,cert);}
    private void del(DSLContext d,String a,String id,long slot,int tx,int cert) {d.execute("insert into delegation_vote values(?,?,?,'ADDR_KEYHASH',?,?,?)",a,id,HASH,slot,tx,cert);}
    private BFGovernanceStorageReaderImpl reader(DSLContext d) {return new BFGovernanceStorageReaderImpl(d,new ObjectMapper());}
    @Test void membershipUsesCanonicalTransactionComparisonsBeforePagination() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            reg(d,"before-cert","STAKE_REGISTRATION",1,0,0); del(d,"before-cert",ID,10,0,0);
            reg(d,"ordinary","STAKE_REGISTRATION",1,0,0); del(d,"ordinary",ID,20,0,0);
            reg(d,"reregistered","STAKE_REGISTRATION",1,0,0); del(d,"reregistered",ID,21,0,0);
            reg(d,"reregistered","STAKE_DEREGISTRATION",22,0,0); reg(d,"reregistered","STAKE_REGISTRATION",23,0,0);
            reg(d,"same-tx-dereg","STAKE_REGISTRATION",1,0,0); del(d,"same-tx-dereg",ID,30,0,0); reg(d,"same-tx-dereg","STAKE_DEREGISTRATION",30,0,1);
            reg(d,"later-reg","STAKE_REGISTRATION",1,0,0); del(d,"later-reg",ID,40,0,0); reg(d,"later-reg","STAKE_REGISTRATION",41,0,0);
            reg(d,"left","STAKE_REGISTRATION",1,0,0); del(d,"left",ID,42,0,0); del(d,"left",OTHER,43,0,0);
            reg(d,"cert-first","STAKE_REGISTRATION",1,0,0); reg(d,"cert-second","STAKE_REGISTRATION",1,0,0);
            del(d,"cert-second",ID,50,0,2); del(d,"cert-first",ID,50,0,1);
            del(d,"never-registered",ID,60,0,0);
            var expected=List.of("before-cert","ordinary","same-tx-dereg","later-reg","cert-first","cert-second");
            var r=reader(d); var identity=BFDRepIdentity.parse(ID);
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAddress()).containsExactlyElementsOf(expected);
            assertThat(r.findDRepDelegators(identity,1,2,Order.asc)).extracting(x->x.getAddress()).containsExactlyElementsOf(expected.subList(2,4));
            assertThat(r.findDRepDelegators(identity,0,100,Order.desc)).extracting(x->x.getAddress()).containsExactlyElementsOf(expected.reversed());
        }
    }
    @Test void amountsExcludeFutureAndNullRewardsAndNeverClampWithdrawals() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES);
            for(String a:List.of("ordinary","negative")) {reg(d,a,"STAKE_REGISTRATION",1,0,0);del(d,a,ID,a.equals("ordinary")?20:30,0,0);}
            d.execute("insert into stake_address_balance values('ordinary',1000,1000,10),('negative',20,1000,10)");
            d.execute("insert into stake_address_balance_current values('ordinary',1000),('negative',20)");
            d.execute("insert into reward values('ordinary',50,10,9,'member'),('ordinary',70,11,10,'leader'),('ordinary',900,12,11,'member'),('ordinary',999,null,9,'member'),('negative',5,10,9,'member')");
            d.execute("insert into reward_rest values('ordinary',30,11),('ordinary',888,12),('ordinary',999,null)");
            d.execute("insert into instant_reward values('ordinary',20,11),('ordinary',888,12),('ordinary',999,null)");
            d.execute("insert into withdrawal values('ordinary',200,10),('negative',100,10)");
            assertThat(reader(d).findDRepDelegators(BFDRepIdentity.parse(ID),0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(970L,-75L);
        }
    }
    private void ordinary(DSLContext d,String address) {
        reg(d,address,"STAKE_REGISTRATION",1,0,0); del(d,address,ID,20,0,0);
        d.execute("insert into stake_address_balance values(?,1000,1000,10)",address);
    }
    @Test void checkpointMatchesFullHistoryAcrossSpendableAndWithdrawalBoundaries() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES); ordinary(d,"boundaries");
            d.execute("insert into block values(102,1020,12)");
            d.execute("insert into adapot_jobs values(11,'REWARD_CALC','COMPLETED')");
            d.execute("insert into ss_max_slot_balances values('state',10)");
            d.execute("insert into ss_pool_rewards values('boundaries',25,10)");
            d.execute("insert into reward values('boundaries',100,9,8,'member'),('boundaries',25,11,9,'member'),('boundaries',17,11,11,'member'),('boundaries',19,12,11,'leader'),('boundaries',999,null,9,'member')");
            d.execute("insert into reward_rest values('boundaries',7,11),('boundaries',999,null)");
            d.execute("insert into instant_reward values('boundaries',9,12),('boundaries',999,null)");
            d.execute("insert into withdrawal values('boundaries',100,10),('boundaries',13,11)");
            var r=reader(d); var identity=BFDRepIdentity.parse(ID);
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1064L);
            d.execute("delete from adapot_jobs"); // full-history fallback remains exactly equal
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1064L);
        }
    }
    @Test void checkpointRequiresMatchingCompletionAndSurvivingUnloggedMarker() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES); ordinary(d,"guard");
            d.execute("insert into reward values('guard',100,10,9,'member')");
            d.execute("insert into ss_pool_rewards values('guard',999,10)");
            d.execute("insert into adapot_jobs values(11,'REWARD_CALC','COMPLETED')");
            var r=reader(d); var identity=BFDRepIdentity.parse(ID);
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1100L);
            d.execute("insert into ss_max_slot_balances values('state',10)");
            d.execute("update ss_pool_rewards set withdrawable_reward=100");
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1100L);
            d.execute("insert into block values(102,1020,12)");
            d.execute("insert into adapot_jobs values(12,'REWARD_CALC','COMPLETED')");
            d.execute("update ss_pool_rewards set withdrawable_reward=999");
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1100L);
            d.execute("delete from adapot_jobs where epoch=12"); // rollback restores the matching completed job
            d.execute("update ss_pool_rewards set withdrawable_reward=100");
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1100L);
            d.execute("delete from ss_max_slot_balances"); // crash truncation invalidates UNLOGGED snapshot
            d.execute("update ss_pool_rewards set withdrawable_reward=999");
            assertThat(r.findDRepDelegators(identity,0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1100L);
        }
    }
    @Test void sameTransactionLifecycleAndSpecialDRepUseTheirCanonicalRegistrationRules() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES); ordinary(d,"regular");
            d.execute("insert into drep_registration values(?,?,?,'UNREG_DREP_CERT',20,0,1)",ID,HASH,"ADDR_KEYHASH");
            assertThat(reader(d).findDRepDelegators(BFDRepIdentity.parse(ID),0,100,Order.asc)).isEmpty();
            d.execute("insert into drep_registration values(?,?,?,'REG_DREP_CERT',20,0,2)",ID,HASH,"ADDR_KEYHASH");
            // Delegation certificate0 is before registration2, but the latest registration tx matches.
            assertThat(reader(d).findDRepDelegators(BFDRepIdentity.parse(ID),0,100,Order.asc)).extracting(x->x.getAddress()).containsExactly("regular");
            reg(d,"special","STAKE_REGISTRATION",1,0,0);
            d.execute("insert into delegation_vote values('special',null,null,'ABSTAIN',30,0,0)");
            assertThat(reader(d).findDRepDelegators(BFDRepIdentity.parse("drep_always_abstain"),0,100,Order.asc)).extracting(x->x.getAddress()).containsExactly("special");
        }
    }
    @Test void concurrentWriterCannotMixBalanceAndRewardSnapshots() throws Exception {
        try(var setup=connection()) {
            var d=DSL.using(setup,SQLDialect.POSTGRES); ordinary(d,"snapshot");
            d.execute("insert into reward values('snapshot',5,11,10,'member')");
        }
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
        var aware=new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(source);
        var config=new org.jooq.impl.DefaultConfiguration(); config.set(SQLDialect.POSTGRES); config.set(aware);
        var changed=new java.util.concurrent.atomic.AtomicBoolean();
        config.set(new org.jooq.impl.DefaultExecuteListenerProvider(new org.jooq.impl.DefaultExecuteListener() {
            @Override public void fetchEnd(org.jooq.ExecuteContext context) {
                if (context.sql()!=null && context.sql().contains("page_delegators") && changed.compareAndSet(false,true)) {
                    var connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(source);
                    try {
                        assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                        assertThat(connection.isReadOnly()).isTrue();
                        try(var writer=BFDRepDelegatorParityTest.this.connection()) {
                            var sql=DSL.using(writer,SQLDialect.POSTGRES);
                            sql.execute("update stake_address_balance set quantity=2000 where address='snapshot'");
                            sql.execute("insert into reward values('snapshot',50,11,10,'member')");
                        }
                    } catch(Exception error) {throw new RuntimeException(error);}
                }
            }
        }));
        var proxy=new org.springframework.aop.framework.ProxyFactory(reader(DSL.using(config))); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var r=(BFGovernanceStorageReaderImpl)proxy.getProxy();
        assertThat(r.findDRepDelegators(BFDRepIdentity.parse(ID),0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(1005L);
        assertThat(changed.get()).isTrue();
        assertThat(r.findDRepDelegators(BFDRepIdentity.parse(ID),0,100,Order.asc)).extracting(x->x.getAmount()).containsExactly(2055L);
    }
    @Test void slowStatementIsCancelledWithinTheServingBudget() throws Exception {
        try(var c=connection()) {
            var d=DSL.using(c,SQLDialect.POSTGRES); ordinary(d,"slow");
            d.execute("alter table reward rename to reward_rows");
            d.execute("insert into reward_rows values('slow',1,11,10,'member')");
            d.execute("create view reward as select r.* from reward_rows r cross join lateral (select pg_sleep(10)) delay");
            long started=System.nanoTime();
            assertThatThrownBy(() -> reader(d).findDRepDelegators(BFDRepIdentity.parse(ID),0,100,Order.asc))
                    .isInstanceOf(org.jooq.exception.DataAccessException.class).hasMessageContaining("canceling statement");
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(5000);
        }
    }

}
