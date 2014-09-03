# CURATOR

# --- !Ups

ALTER TABLE uri_recommendation
  DROP COLUMN deleted;

ALTER TABLE uri_recommendation
  ADD COLUMN trashed BOOLEAN NOT NULL DEFAULT false;

insert into evolutions (name, description) values('230.sql', 'add trashed column, drop deleted column');

# --- !Downs