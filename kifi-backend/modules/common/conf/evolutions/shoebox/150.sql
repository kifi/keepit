# ABOOK

# --- !Ups

ALTER TABLE rich_social_connection drop column if exists invitation;
ALTER TABLE rich_social_connection add column if not exists invitation_sent Int NOT NULL DEFAULT 0;

insert into evolutions (name, description) values('150.sql', 'add column invitation_sent to rich_social_connection and drop column invitation');

# --- !Downs
