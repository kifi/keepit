# SHOEBOX

# --- !Ups

ALTER TABLE keep_source_attribution ADD COLUMN keep_id BIGINT(20) DEFAULT NULL AFTER updated_at;
insert into evolutions(name, description) values('414.sql', 'add keep_source_attribution.keep_id, remove bookmark.source_attribution_id');

# --- !Downs
