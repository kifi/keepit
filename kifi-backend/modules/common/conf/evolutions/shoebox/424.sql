# SHOEBOX

# --- !Ups

CREATE TABLE slack_channel (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_channel_id VARCHAR(32) NOT NULL,
  slack_channel_name text NOT NULL,
  last_notification_at DATETIME DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY slack_channel_u_team_id_channel_id (slack_team_id, slack_channel_id)
);
insert into evolutions(name, description) values('424.sql', 'create table slack_channel');

# --- !Downs
