# ELIZA

# --- !Ups

ALTER TABLE notification DROP CONSTRAINT notification_group_identifier;

-- mysql
-- ALTER TABLE notification DROP INDEX notification_group_identifier;

alter table notification add CONSTRAINT notification_group_identifier unique index(recipient, kind, group_identifier);

insert into evolutions (name, description) values(
  '391.sql',
  'Make notification group_identifer non unique and add unique constraint of (recipient, kind, kind group_identifier)'
);

# --- !Downs
