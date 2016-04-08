# SHOEBOX

# --- !Ups

CREATE TABLE slack_kifibot_feedback (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_user_id VARCHAR(32) NOT NULL,
  last_ingested_message_timestamp VARCHAR(32) DEFAULT NULL,
  last_processing_at DATETIME DEFAULT NULL,
  last_processed_at DATETIME DEFAULT NULL,
  next_ingestion_at DATETIME NOT NULL,

  PRIMARY KEY(id),
  CONSTRAINT slack_kifibot_feedback_f_slack_team_id_slack_user_id FOREIGN KEY (slack_team_id, slack_user_id) references slack_team_membership(slack_team_id, slack_user_id)
);

INSERT INTO evolutions (name, description) VALUES('438.sql', 'track kifibot feedback from slack');

# --- !Downs
