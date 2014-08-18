# HEIMDAL

# --- !Ups

-- MySQL:
ALTER TABLE keep_click DROP COLUMN clicker_id;

-- MySQL:
-- ALTER TABLE keep_click DROP INDEX keep_click_uuid;
ALTER TABLE keep_click DROP search_uuid;

ALTER TABLE keep_click ADD COLUMN hit_uuid varchar(36) NOT NULL;
ALTER TABLE keep_click ADD INDEX keep_click_hit_uuid(hit_uuid);

insert into evolutions (name, description) values('160.sql', 'modify keep_click table');

# --- !Downs
