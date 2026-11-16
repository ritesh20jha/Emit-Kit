-- ShedLock's lock table (https://github.com/lukas-krecan/ShedLock): one row per scheduled job, so a
-- job runs on one replica at a time (ops-03). Timestamps are timestamptz, as in the other migrations.

CREATE TABLE shedlock (
    name        varchar(64)  PRIMARY KEY,
    lock_until  timestamptz  NOT NULL,
    locked_at   timestamptz  NOT NULL,
    locked_by   varchar(255) NOT NULL
);
