# SHOEBOX

# --- !Ups

alter TABLE slack_channel_to_library add column mute_digest_notifications boolean NOT NULL DEFAULT false;
alter TABLE slack_team add column mute_digest_notifications boolean NOT NULL DEFAULT false;

insert into evolutions(name, description) values('426.sql', 'add mute_digest_notifications to slack_channel_to_library and slack_team');

# --- !Downs
