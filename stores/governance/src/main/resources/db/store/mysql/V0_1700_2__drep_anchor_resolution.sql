-- Persist exact DRep anchor documents and ordered resolution observations.
CREATE TABLE drep_anchor_resolution (
    tx_hash varchar(64) NOT NULL,
    cert_index integer NOT NULL,
    drep_id varchar(255) NOT NULL,
    drep_hash varchar(56) NOT NULL,
    cred_type varchar(40) NOT NULL,
    registration_slot bigint NOT NULL,
    anchor_url longtext NOT NULL,
    anchor_hash varchar(64) NOT NULL,
    state varchar(10) NOT NULL DEFAULT 'PENDING',
    selected_generation bigint NOT NULL DEFAULT 0,
    lease_generation bigint NOT NULL DEFAULT 0,
    lease_token varchar(36),
    lease_until timestamp,
    raw_bytes longblob,
    parsed_json json,
    body_hash varchar(64),
    source_kind varchar(40),
    source_receipt_sha256 varchar(64),
    source_item_ordinal integer,
    observed_at timestamp,
    source_capture_started_at timestamp,
    source_capture_completed_at timestamp,
    completed_at timestamp,
    next_attempt_at timestamp,
    current_error_code varchar(40),
    current_error_message longtext,
    PRIMARY KEY(tx_hash, cert_index),
    FOREIGN KEY(tx_hash, cert_index) REFERENCES drep_registration(tx_hash, cert_index) ON DELETE CASCADE,
    CHECK(state IN ('PENDING','SUCCESS','FAILED')),
    CHECK(cred_type IN ('ADDR_KEYHASH','SCRIPTHASH') AND length(anchor_hash)=64),
    CHECK(raw_bytes IS NULL OR octet_length(raw_bytes)<=3000000),
    CHECK(selected_generation >= 0 AND lease_generation >= selected_generation),
    CHECK((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK(source_kind IS NULL OR source_kind IN ('NATIVE_FETCH','RETAINED_REFERENCE_IMPORT')),
    CHECK((source_kind IS NULL OR source_kind<>'RETAINED_REFERENCE_IMPORT') OR
          (source_receipt_sha256 IS NOT NULL AND length(source_receipt_sha256)=64 AND source_item_ordinal>=0
           AND source_capture_started_at IS NOT NULL AND source_capture_completed_at IS NOT NULL
           AND source_capture_completed_at>=source_capture_started_at)),
    CHECK(state='PENDING' OR (source_kind IS NOT NULL AND completed_at IS NOT NULL
           AND (source_kind='RETAINED_REFERENCE_IMPORT' OR observed_at IS NOT NULL))),
    CHECK((state='PENDING' AND selected_generation=0 AND raw_bytes IS NULL AND parsed_json IS NULL
           AND body_hash IS NULL AND current_error_code IS NULL AND current_error_message IS NULL)
       OR (state='SUCCESS' AND selected_generation>0 AND raw_bytes IS NOT NULL AND parsed_json IS NOT NULL
           AND body_hash=anchor_hash AND current_error_code IS NULL AND current_error_message IS NULL)
       OR (state='FAILED' AND selected_generation>0 AND raw_bytes IS NULL AND parsed_json IS NULL
           AND body_hash IS NULL AND current_error_code IS NOT NULL AND current_error_message IS NOT NULL))
);
CREATE INDEX idx_drep_anchor_due ON drep_anchor_resolution(state, next_attempt_at, lease_until);
CREATE TABLE drep_anchor_fetch_attempt (
    tx_hash varchar(64) NOT NULL,
    cert_index integer NOT NULL,
    resolution_generation bigint NOT NULL,
    attempt_ordinal integer NOT NULL,
    candidate_url longtext,
    result_kind varchar(40) NOT NULL,
    error_code varchar(40),
    exact_message longtext,
    observed_http_status integer,
    expected_hash varchar(64),
    observed_hash varchar(64),
    observed_content_type text,
    observed_size bigint,
    started_at timestamp,
    completed_at timestamp,
    source_kind varchar(40) NOT NULL,
    source_receipt_sha256 varchar(64),
    PRIMARY KEY(tx_hash, cert_index, resolution_generation, attempt_ordinal),
    FOREIGN KEY(tx_hash, cert_index) REFERENCES drep_anchor_resolution(tx_hash, cert_index) ON DELETE CASCADE,
    CHECK(resolution_generation>0 AND attempt_ordinal>=0),
    CHECK(source_kind IN ('NATIVE_FETCH','RETAINED_REFERENCE_IMPORT')),
    CHECK(source_kind<>'RETAINED_REFERENCE_IMPORT' OR result_kind='REFERENCE_OUTCOME'),
    CHECK(source_kind<>'NATIVE_FETCH' OR (result_kind<>'REFERENCE_OUTCOME' AND candidate_url IS NOT NULL
          AND started_at IS NOT NULL AND completed_at IS NOT NULL AND completed_at>=started_at)),
    CHECK(observed_http_status IS NULL OR observed_http_status BETWEEN 100 AND 599),
    CHECK(observed_size IS NULL OR observed_size>=0),
    CHECK(result_kind<>'REFERENCE_OUTCOME' OR (candidate_url IS NULL AND observed_http_status IS NULL
       AND observed_hash IS NULL AND observed_content_type IS NULL AND observed_size IS NULL
       AND started_at IS NULL AND completed_at IS NULL AND source_receipt_sha256 IS NOT NULL))
);
