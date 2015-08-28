# ELIZA

# --- !Ups

ALTER TABLE notification ADD COLUMN external_id varchar(36) DEFAULT NULL;

insert into evolutions (name, description) values('389.sql', 'Add external_id to notification');

# --- !Downs
