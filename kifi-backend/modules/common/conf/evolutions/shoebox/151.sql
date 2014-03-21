# ABOOK

# --- !Ups

ALTER TABLE rich_social_connection drop column if exists invitation;
ALTER TABLE rich_social_connection drop column if exists invitation_count;
ALTER TABLE rich_social_connection add column if not exists invitations_sent Int NOT NULL DEFAULT 0;
ALTER TABLE rich_social_connection add column if not exists invited_by Int NOT NULL DEFAULT 0;

insert into evolutions (name, description) values('151.sql', 'add columns invited_by and invitation_sent to rich_social_connection and drop columns invitation and invitation_count');

# --- !Downs