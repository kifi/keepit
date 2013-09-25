#Shoebox

# --- !Ups

alter table renormalized_url
	add column old_uri_id bigint(20) Null;

insert into evolutions (name, description) values('103.sql', 'add old_uri_id to renormalized_url');

# --- !Downs