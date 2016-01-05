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

  PRIMARY KEY(id),
  UNIQUE KEY slack_team_u_slack_team_id (slack_team_id),
  INDEX slack_team_i_organization_id (organization_id)
);
insert into evolutions(name, description) values('422.sql', 'create table slack_team');

# --- !Downs
