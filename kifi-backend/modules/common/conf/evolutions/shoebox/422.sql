# SHOEBOX

# --- !Ups

CREATE TABLE slack_team (
  id BIGINT(20) NOT NULL AUTO_INCREMENT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  state VARCHAR(20) NOT NULL,
  slack_team_id VARCHAR(32) NOT NULL,
  slack_team_name VARCHAR(512) NOT NULL,
  organization_id BIGINT(20) DEFAULT NULL,
  last_channel_created_at VARCHAR(32) DEFAULT NULL,
  general_channel_id VARCHAR(32) DEFAULT NULL,
  last_digest_notification_at DATETIME DEFAULT NULL,
  public_channels_last_synced_at DATETIME DEFAULT NULL,
  channels_synced MEDIUMTEXT NOT NULL,

  PRIMARY KEY(id),
  UNIQUE KEY slack_team_u_slack_team_id (slack_team_id),
  UNIQUE KEY slack_team_u_organization_id (organization_id)
  CONSTRAINT slack_team_f_organization FOREIGN KEY organization_id REFERENCES organization(id)
);
insert into evolutions(name, description) values('422.sql', 'create table slack_team');

# --- !Downs
