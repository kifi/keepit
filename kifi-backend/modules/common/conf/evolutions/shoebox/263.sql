# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation ADD COLUMN topic1 smallint(6) unsigned;
ALTER TABLE uri_recommendation ADD COLUMN topic2 smallint(6) unsigned;

INSERT INTO evolutions (name, description) VALUES('263.sql', 'add topic1 and topic2 to uri_recommendation table');

# --- !Downs
