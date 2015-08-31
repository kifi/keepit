# SHOEBOX

# --- !Ups

ALTER TABLE paid_account ADD COLUMN "locked_for_processing" boolean DEFAULT NULL;

insert into evolutions (name, description) values('387.sql', 'adding locked_for_processing column to paid_account');

# --- !Downs
