# ELIZA

# --- !Ups

ALTER TABLE notification DROP CONSTRAINT notification_group_identifier;

CREATE INDEX notification_i_recipient_kind_group_identifier ON notification (recipient, kind, group_identifier);

insert into evolutions (name, description) values(
  '434.sql',
  'Make notification.group_identifier non-unique'
);

# --- !Downs
