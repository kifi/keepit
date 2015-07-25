# Shoebox

# --- !Ups

ALTER TABLE `bookmark`
	ADD COLUMN `organization_id` bigint(20) DEFAULT NULL;

insert into evolutions (name, description) values('343.sql', 'add organization_id column to bookmark');

# --- !Downs
