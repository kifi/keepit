# SHOEBOX

# --- !Ups

CREATE TABLE slack_team_membership (
	id BIGINT(20) NOT NULL AUTO_INCREMENT,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	state VARCHAR(20) NOT NULL,
	user_id BIGINT(20) NOT NULL,
	slack_user_id VARCHAR(32) NOT NULL,
	slack_username VARCHAR(32) NOT NULL,
	slack_team_id VARCHAR(32) NOT NULL,
    slack_team_name VARCHAR(512) NOT NULL,
	token VARCHAR(512) NOT NULL,
	scopes text NOT NULL,

	PRIMARY KEY(id),
	UNIQUE KEY slack_team_membership_u_slack_team_id_slack_user_id (slack_team_id, slack_user_id),
	INDEX slack_team_membership_i_user_id_slack_team_id_slack_user_id (user_id, slack_user_id, slack_team_id)
);

CREATE TABLE slack_incoming_webhook_info (
	id BIGINT(20) NOT NULL AUTO_INCREMENT,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	state VARCHAR(20) NOT NULL,
	owner_id BIGINT(20) NOT NULL,
	slack_user_id VARCHAR(32) NOT NULL,
	slack_team_id VARCHAR(32) NOT NULL,
	slack_channel_id VARCHAR(32) DEFAULT NULL,
	slack_channel_name VARCHAR(32) NOT NULL,
	url varchar(2048) NOT NULL,
	config_url varchar(2048) NOT NULL,
	last_posted_at DATETIME DEFAULT NULL,
	last_failed_at DATETIME DEFAULT NULL,
	last_failure text DEFAULT NULL,

	PRIMARY KEY(id),
	INDEX slack_incoming_webhook_info_i_slack_team_id_slack_channel_id (slack_team_id, slack_channel_id),
	CONSTRAINT slack_incoming_webhook_info_f_slack_team_membership FOREIGN KEY (owner_id, slack_user_id, slack_team_id) REFERENCES slack_team_membership(user_id, slack_user_id, slack_team_id)
);

CREATE TABLE library_to_slack_channel (
	id BIGINT(20) NOT NULL AUTO_INCREMENT,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	state VARCHAR(20) NOT NULL,
	owner_id BIGINT(20) NOT NULL,
	slack_user_id VARCHAR(32) NOT NULL,
	slack_team_id VARCHAR(32) NOT NULL,
	slack_channel_id VARCHAR(32) DEFAULT NULL,
    slack_channel_name VARCHAR(32) NOT NULL,
    library_id BIGINT(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_processed_at DATETIME DEFAULT NULL,
    last_keep_id BIGINT(20) DEFAULT NULL,

	PRIMARY KEY(id),
	UNIQUE KEY library_to_slack_channel_u_slack_team_id_slack_channel_id_library_id (slack_team_id, slack_channel_id, library_id),
	CONSTRAINT library_to_slack_channel_f_slack_team_membership FOREIGN KEY (owner_id, slack_user_id, slack_team_id) REFERENCES slack_team_membership(user_id, slack_user_id, slack_team_id)
);

CREATE TABLE slack_channel_to_library (
	id BIGINT(20) NOT NULL AUTO_INCREMENT,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	state VARCHAR(20) NOT NULL,
	owner_id BIGINT(20) NOT NULL,
	slack_user_id VARCHAR(32) NOT NULL,
	slack_team_id VARCHAR(32) NOT NULL,
	slack_channel_id VARCHAR(32) DEFAULT NULL,
    slack_channel_name VARCHAR(32) NOT NULL,
    library_id BIGINT(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_processed_at DATETIME DEFAULT NULL,
    last_message_at DATETIME DEFAULT NULL,

	PRIMARY KEY(id),
	UNIQUE KEY slack_channel_to_library_u_slack_team_id_slack_channel_id_library_id (slack_team_id, slack_channel_id, library_id),
	CONSTRAINT slack_channel_library_f_slack_team_membership FOREIGN KEY (owner_id, slack_user_id, slack_team_id) REFERENCES slack_team_membership(user_id, slack_user_id, slack_team_id)
);

insert into evolutions(name, description) values('409.sql', 'create tables slack_team_membership, slack_incoming_webhook_info, library_to_slack_channel, slack_channel_to_library');

# --- !Downs
