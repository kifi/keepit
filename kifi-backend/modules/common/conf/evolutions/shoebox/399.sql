# SHOEBOX

# --- !Ups

ALTER TABLE bookmark MODIFY COLUMN libraries_hash int(10) NOT NULL;
ALTER TABLE bookmark MODIFY COLUMN participants_hash int(10) NOT NULL;
ALTER TABLE bookmark ADD COLUMN message_thread_id bigint(20) DEFAULT NULL;
ALTER TABLE bookmark ADD COLUMN async_status varchar(50) NOT NULL;

ALTER TABLE message_thread ADD UNIQUE INDEX bookmark_u_message_thread_id ON message_thread_id;

insert into evolutions(name, description) values('399.sql', 'made keep hashes (libraries, participants) not null, added message_thread_id (w/ unique index) and async_status');

# --- !Downs
