# ABOOK

# --- !Ups


ALTER TABLE abook_info ADD COLUMN external_id varchar(36) NOT NULL;
ALTER TABLE abook_info ADD CONSTRAINT abook_info_u_external_id UNIQUE (external_id);

insert into evolutions (name, description) values('163.sql', 'add external_id to abook_info table');

# --- !Downs
