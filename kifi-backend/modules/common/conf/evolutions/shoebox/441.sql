# ELIZA

# --- !Ups

ALTER TABLE message_thread ADD COLUMN num_messages INT(10) DEFAULT NULL;

insert into evolutions (name, description) values('441.sql', 'introduce message_thread.num_messages');

# --- !Downs
