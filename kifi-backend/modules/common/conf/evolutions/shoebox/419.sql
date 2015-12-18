# ELIZA

# --- !Ups

ALTER TABLE message_thread MODIFY COLUMN keep_id BIGINT(20) NOT NULL;
insert into evolutions(name, description) values('419.sql', 'make message_thread.keep_id NOT NULL');

# --- !Downs
