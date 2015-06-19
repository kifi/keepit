# SHOEBOX

# --- !Ups

ALTER TABLE organization
	ADD COLUMN base_permissions text NOT NULL;

insert into evolutions(name, description) values('344.sql', 'add base permissions to each organization');

# --- !Downs
