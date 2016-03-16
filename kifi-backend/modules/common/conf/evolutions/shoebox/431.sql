# SHOEBOX

# --- !Ups

CREATE TABLE slack_push_for_keep (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_channel_id VARCHAR(32) NOT NULL,
  integration_id BIGINT(20) NOT NULL,
  keep_id BIGINT(20) NOT NULL,
  slack_timestamp VARCHAR(32) NOT NULL,
  text TEXT NOT NULL,
  last_known_editability VARCHAR(32) NOT NULL DEFAULT 'editable',
  message_request TEXT DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY slack_push_for_keep_u_integration_id_keep_id (integration_id, keep_id),
  CONSTRAINT slack_push_for_keep_f_integration_id FOREIGN KEY (integration_id) REFERENCES library_to_slack_channel(id),
  CONSTRAINT slack_push_for_keep_f_keep_id FOREIGN KEY (keep_id) REFERENCES bookmark(id)
);

CREATE TABLE slack_push_for_message (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_channel_id VARCHAR(32) NOT NULL,
  integration_id BIGINT(20) NOT NULL,
  message_id BIGINT(20) NOT NULL,
  slack_timestamp VARCHAR(32) NOT NULL,
  text TEXT NOT NULL,
  last_known_editability VARCHAR(32) NOT NULL DEFAULT 'editable',
  message_request TEXT DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY slack_push_for_message_u_integration_id_message_id (integration_id, message_id),
  CONSTRAINT slack_push_for_message_f_integration_id FOREIGN KEY (integration_id) REFERENCES library_to_slack_channel(id)
);

insert into evolutions(name, description) values('431.sql', 'create tables for keep/message pushes to slack');

# --- !Downs
