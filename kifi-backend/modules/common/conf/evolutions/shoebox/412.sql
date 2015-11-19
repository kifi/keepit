# ELIZA

# --- !Ups

ALTER TABLE message_thread ADD COLUMN keep_id bigint(20) DEFAULT NULL;
ALTER TABLE message_thread ADD UNIQUE INDEX message_thread_u_keep_id ON keep_id;

ALTER TABLE message_thread ADD COLUMN async_status varchar(50) NOT NULL;

insert into evolutions (name, description) values('412.sql', 'Add keep_id into message_thread and index on it');

# --- !Downs
