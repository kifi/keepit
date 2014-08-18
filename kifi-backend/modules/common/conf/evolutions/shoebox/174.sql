# HEIMDAL

# --- !Ups

ALTER TABLE user_bookmark_clicks ADD COLUMN if not exists rekeep_count int default 0;
ALTER TABLE user_bookmark_clicks ADD COLUMN if not exists rekeep_total_count int default 0;
ALTER TABLE user_bookmark_clicks ADD COLUMN if not exists rekeep_degree int default 0;

CREATE INDEX user_bookmark_clicks_rekeep_direct_count ON user_bookmark_clicks(rekeep_count);
CREATE INDEX user_bookmark_clicks_rekeep_total_count ON user_bookmark_clicks(rekeep_total_count);

insert into evolutions (name, description) values('174.sql', 'add rekeep stats to user_bookmark_clicks table');

# --- !Downs
