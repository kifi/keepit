# SHOEBOX

# --- !Ups

ALTER TABLE paid_plan ADD COLUMN features text NOT NULL;

ALTER TABLE paid_account ADD COLUMN feature_settings text NOT NULL;

insert into evolutions(name, description) values('394.sql', 'add features to paid_plan, add feature_settings to paid_account');

# --- !Downs
