# SHOEBOX

# --- !Ups

ALTER TABLE raw_keep ALTER COLUMN original_json SET NULL;
-- Mysql: ALTER TABLE raw_keep MODIFY original_json TEXT NULL;

insert into evolutions (name, description) values('167.sql', 'making original_json nullable in raw_keep');

# --- !Downs
