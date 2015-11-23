# ELIZA

# --- !Ups

ALTER TABLE message_thread ADD COLUMN keep_id BIGINT(20) DEFAULT NULL;
insert into evolutions(name, description) values('412.sql', 'added an optional keep_id to message_thread');

# --- !Downs
