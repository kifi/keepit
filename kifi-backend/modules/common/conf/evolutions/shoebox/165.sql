# ABOOK

# --- !Ups

ALTER TABLE abook_info ADD COLUMN external_id varchar(36);
ALTER TABLE abook_info ADD CONSTRAINT abook_info_u_external_id UNIQUE (external_id);
-- UPDATE abook_info SET external_id = UUID(); --Prod
CREATE INDEX abook_info_i_ext_id ON abook_info (external_id(8));

insert into evolutions (name, description) values('165.sql', 'add external_id to abook_info table');

# --- !Downs
