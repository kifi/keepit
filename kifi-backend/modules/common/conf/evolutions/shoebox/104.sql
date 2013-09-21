#Shoebox

# --- !Ups

--- alter table renormalized_url
---	modify column old_uri_id bigint(20) not Null;

alter table renormalized_url
	alter column old_uri_id bigint(20) not Null;

insert into evolutions (name, description) values('104.sql', 'make old_uri_id not null');

# --- !Downs