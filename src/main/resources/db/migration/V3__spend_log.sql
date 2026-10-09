CREATE TABLE spend_log (id INTEGER PRIMARY KEY AUTOINCREMENT, spent_at INTEGER NOT NULL, micro_usd INTEGER NOT NULL);
CREATE INDEX spend_log_time ON spend_log(spent_at);
