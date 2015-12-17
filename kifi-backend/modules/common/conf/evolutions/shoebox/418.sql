# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN message_seq bigint(20) DEFAULT NULL;
insert into evolutions(name, description) values('418.sql', 'add bookmark.message_seq');

# --- !Downs
