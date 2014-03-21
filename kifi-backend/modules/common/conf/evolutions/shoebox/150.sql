#Shoebox

# --- !Ups
alter table friend_request
	add column message_handle bigint(20);

insert into evolutions (name, description) values('150.sql', 'add message_handle to friend_request');

# --- !Downs