# SHOEBOX

# --- !Ups

DROP TABLE uri_normalization_rule;

insert into evolutions (name, description) values('93.sql', 'dropping uri_normalization_rule table');

# --- !Downs
