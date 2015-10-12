# SHOEBOX

# --- !Ups

ALTER TABLE paid_plan ADD COLUMN display_name VARCHAR(256) NOT NULL DEFAULT '';

insert into evolutions(name, description) values('401.sql', 'add paid_plan.displayName');

# --- !Downs
