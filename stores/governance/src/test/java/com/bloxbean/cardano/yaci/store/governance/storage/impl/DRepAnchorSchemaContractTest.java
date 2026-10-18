package com.bloxbean.cardano.yaci.store.governance.storage.impl;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class DRepAnchorSchemaContractTest {
    @Test void persistenceSchemaMustExistOutsidePrimaryNexusMigrations() {
        assertThat(getClass().getResource("/db/governance-anchor-resolution/postgresql.sql"))
                .as("governance raw-document and ordered attempt persistence schema").isNotNull();
    }
    @Test void emittedH2MigrationCreatesBothTablesAndNativeForeignKeyCascade() throws Exception {
        try(var connection=java.sql.DriverManager.getConnection("jdbc:h2:mem:anchor_"+java.util.UUID.randomUUID()+";DATABASE_TO_LOWER=TRUE")) {
            var statement=connection.createStatement();
            statement.execute("CREATE TABLE drep_registration(tx_hash varchar(64),cert_index int,PRIMARY KEY(tx_hash,cert_index))");
            String sql=new String(getClass().getResourceAsStream("/db/store/h2/V0_1700_2__drep_anchor_resolution.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(String query:sql.split(";"))if(!query.isBlank())statement.execute(query);
            statement.execute("INSERT INTO drep_registration VALUES('a',0)");
            statement.execute("INSERT INTO drep_anchor_resolution(tx_hash,cert_index,drep_id,drep_hash,cred_type,registration_slot,anchor_url,anchor_hash) VALUES('a',0,'drep','hash','ADDR_KEYHASH',100,'https://example.com','"+"f".repeat(64)+"')");
            statement.execute("DELETE FROM drep_registration");
            try(var count=statement.executeQuery("SELECT count(*) FROM drep_anchor_resolution")){count.next();assertThat(count.getInt(1)).isZero();}
        }
    }
}
