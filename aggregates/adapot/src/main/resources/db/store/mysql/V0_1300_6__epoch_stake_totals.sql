CREATE TABLE epoch_stake_total (
    epoch integer PRIMARY KEY,
    total numeric(38),
    row_count bigint NOT NULL
);

CREATE TABLE epoch_stake_total_state (
    id integer PRIMARY KEY,
    prune_before_epoch integer NOT NULL
);

INSERT INTO epoch_stake_total_state (id, prune_before_epoch) VALUES (1, -2147483648);
