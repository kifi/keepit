# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  DROP COLUMN marked_bad;

insert into evolutions (name, description) values('234.sql', 'drop marked_bad column');

# --- !Downs