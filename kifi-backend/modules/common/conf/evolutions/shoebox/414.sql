# SHOEBOX

# --- !Ups

ALTER TABLE keep_source_attribution ADD COLUMN keep_id BIGINT(20) NOT NULL AFTER updated_at;
ALTER TABLE keep_source_attribution ADD COLUMN author VARCHAR(64) DEFAULT NULL AFTER keep_id;
CREATE UNIQUE INDEX keep_source_attribution_u_keep_id ON keep_source_attribution (keep_id);
CREATE INDEX keep_source_attribution_u_author ON keep_source_attribution(author);
-- ALTER TABLE bookmark DROP FOREIGN KEY bookmark_f_source_attribution;
ALTER TABLE bookmark DROP COLUMN source_attribution_id;
insert into evolutions(name, description) values('414.sql', 'add keep_source_attribution.keep_id, remove bookmark.source_attribution_id');

# --- !Downs
