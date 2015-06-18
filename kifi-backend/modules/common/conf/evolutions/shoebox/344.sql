# SHOEBOX

# --- !Ups

ALTER TABLE organization
	ADD COLUMN base_permissions varchar(1024) NOT NULL;

insert into evolutions(name, description) values('344.sql', 'add base permissions to each organization');

# --- !Downs
