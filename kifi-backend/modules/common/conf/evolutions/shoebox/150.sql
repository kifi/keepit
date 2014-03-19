# ABOOK

# --- !Ups

ALTER TABLE rich_social_connection add invitation_sent Int NOT NULL DEFAULT 0;
ALTER TABLE rich_social_connection drop invitation;

insert into evolutions (name, description) values('150.sql', 'add column invitation_sent to rich_social_connection and drop column invitation');

# --- !Downs
