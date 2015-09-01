# SHOEBOX

# --- !Ups

ALTER TABLE paid_account ADD COLUMN "frozen" boolean NOT NULL DEFAULT false;

insert into evolutions (name, description) values('388.sql', 'adding frozen column to paid_account');

# --- !Downs
