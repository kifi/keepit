# Shoebox

# --- !Ups

ALTER TABLE organization
	ADD COLUMN base_permissions text NOT NULL;

insert into evolutions (name, description) values('347.sql', 'add base_permissions to organization');

# --- !Downs
