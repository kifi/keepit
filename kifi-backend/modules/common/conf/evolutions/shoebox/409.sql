# SHOEBOX

# --- !Ups

CREATE TABLE slack_team_membership (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  seq bigint(20) NOT NULL,
  user_id BIGINT(20) DEFAULT NULL,
  slack_user_id VARCHAR(32) NOT NULL,
  slack_username VARCHAR(32) DEFAULT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  token VARCHAR(512) NOT NULL,
  scopes text NOT NULL,
  slack_user text DEFAULT NULL,
  private_channels_last_synced_at DATETIME DEFAULT NULL,
  last_personal_digest_at DATETIME DEFAULT NULL,
  last_processing_at DATETIME DEFAULT NULL,
  last_processed_at DATETIME DEFAULT NULL,
  personal_digest_setting VARCHAR(32) NOT NULL,
  next_personal_digest_at DATETIME NOT NULL,
  last_ingested_message_timestamp VARCHAR(32) DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY slack_team_membership_u_slack_team_id_slack_user_id (slack_team_id, slack_user_id),
  UNIQUE KEY slack_team_membership_u_user_id_slack_team_id (user_id, slack_team_id),
  INDEX slack_team_membership_i_seq (seq),
  INDEX slack_team_membership_i_personal_digest (last_processing_at,next_personal_digest_at)
);

ALTER TABLE slack_team_membership ADD INDEX slack_team_membership_i_slack_user_id (slack_user_id);

-- MySQL:
-- CREATE TABLE slack_team_membership_sequence (id bigint(20) NOT NULL);
-- INSERT INTO slack_team_membership_sequence VALUES (0);

CREATE SEQUENCE slack_team_membership_sequence;

CREATE TABLE slack_incoming_webhook_info (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_user_id VARCHAR(32) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_channel_id VARCHAR(32) NOT NULL,
  url varchar(2048) NOT NULL,
  config_url varchar(2048) NOT NULL,
  last_posted_at DATETIME DEFAULT NULL,
  last_failed_at DATETIME DEFAULT NULL,
  last_failure text DEFAULT NULL,

  PRIMARY KEY(id),
  INDEX slack_incoming_webhook_info_i_team_id_channel_id (slack_team_id, slack_channel_id),
  INDEX slack_incoming_webhook_info_i_team_id_user_id (slack_team_id, slack_user_id),
  CONSTRAINT slack_incoming_webhook_info_f_slack_team_membership FOREIGN KEY (slack_team_id, slack_user_id) REFERENCES slack_team_membership(slack_team_id, slack_user_id)
);

CREATE TABLE library_to_slack_channel (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_user_id VARCHAR(32) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_channel_id VARCHAR(32) NOT NULL,
  library_id BIGINT(20) NOT NULL,
  status VARCHAR(32) NOT NULL,
  changed_status_at DATETIME NOT NULL,
  last_processed_at DATETIME DEFAULT NULL,
  last_processed_ktl BIGINT(20) DEFAULT NULL,
  last_processed_msg BIGINT(20) DEFAULT NULL,
  last_processed_keep_seq BIGINT(20) DEFAULT NULL,
  last_processed_msg_seq BIGINT(20) DEFAULT NULL,
  last_processing_at DATETIME DEFAULT NULL,
  next_push_at DATETIME DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY library_to_slack_channel_u_team_id_channel_id_library_id (slack_team_id, slack_channel_id, library_id),
  INDEX library_to_slack_channel_i_library_id (library_id),
  CONSTRAINT library_to_slack_channel_f_slack_team_membership FOREIGN KEY (slack_team_id, slack_user_id) REFERENCES slack_team_membership(slack_team_id, slack_user_id),
  INDEX library_to_slack_channel_i_pushable (state, status, last_processing_at, next_push_at)
);

CREATE TABLE slack_channel_to_library (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_user_id VARCHAR(32) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_channel_id VARCHAR(32) NOT NULL,
  library_id BIGINT(20) NOT NULL,
  status VARCHAR(32) NOT NULL,
  changed_status_at DATETIME NOT NULL,
  next_ingestion_at DATETIME DEFAULT NULL,
  last_ingesting_at DATETIME DEFAULT NULL,
  last_ingested_at DATETIME DEFAULT NULL,
  last_message_timestamp VARCHAR(32) DEFAULT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY slack_channel_to_library_u_team_id_channel_id_library_id (slack_team_id, slack_channel_id, library_id),
  CONSTRAINT slack_channel_to_library_f_slack_team_membership FOREIGN KEY (slack_team_id, slack_user_id) REFERENCES slack_team_membership(slack_team_id, slack_user_id),
  INDEX slack_channel_to_library_i_ingestable (state, status, last_ingesting_at, next_ingestion_at)
);

insert into evolutions(name, description) values('409.sql', 'create tables slack_team_membership, slack_incoming_webhook_info, library_to_slack_channel, slack_channel_to_library');

# --- !Downs
