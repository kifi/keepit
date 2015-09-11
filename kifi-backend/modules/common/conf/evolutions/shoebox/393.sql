# SHOEBOX

# --- !Ups

ALTER TABLE paid_plan ADD COLUMN features NOT NULL;

ALTER TABLE paid_account ADD COLUMN settings_by_feature text NOT NULL;

insert into evolutions(name, description) values('393.sql', 'add features to paid_plan, add settings_by_feature to paid_account');

# --- !Downs
