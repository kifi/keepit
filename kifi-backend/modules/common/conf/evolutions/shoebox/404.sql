# SHOEBOX

# --- !Ups

CREATE TABLE credit_code_info (
	id BIGINT(20) NOT NULL AUTO_INCREMENT,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	state VARCHAR(20) NOT NULL,
	code VARCHAR(128) NOT NULL,
	kind VARCHAR(64) NOT NULL,
	credit int(10) NOT NULL,
	status VARCHAR(32) NOT NULL,
	referrer_user_id BIGINT(20) DEFAULT NULL,
	referrer_organization_id BIGINT(20) DEFAULT NULL,

	PRIMARY KEY(id),
	UNIQUE KEY credit_code_info_u_code (code),
	CONSTRAINT credit_code_info_f_user FOREIGN KEY (referrer_user_id) REFERENCES user(id),
	CONSTRAINT credit_code_info_f_organization FOREIGN KEY (referrer_organization_id) REFERENCES organization(id)
);

CREATE INDEX account_event_i_account_id_id_credit ON account_event (account_id, id, credit_change);
CREATE TABLE credit_reward (
	id BIGINT(20) NOT NULL AUTO_INCREMENT,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	state VARCHAR(20) NOT NULL,
	account_id BIGINT(20) NOT NULL,
	credit INT(10) NOT NULL,
	applied BIGINT(20) DEFAULT NULL,
	kind VARCHAR(32) NOT NULL,
	status VARCHAR(32) NOT NULL,
	info VARCHAR(256) NOT NULL,
	unrepeatable VARCHAR(64) DEFAULT NULL,
	code VARCHAR(128) DEFAULT NULL,
	single_use TINYINT(1) DEFAULT NULL,
	used_by BIGINT(20) DEFAULT NULL,

	PRIMARY KEY(id),
	UNIQUE KEY credit_reward_u_code_single_use (code, single_use),
	UNIQUE KEY credit_reward_u_unrepeatable (unrepeatable),
	CONSTRAINT credit_reward_f_code FOREIGN KEY (code) REFERENCES credit_code_info(code),
	CONSTRAINT credit_reward_f_used_by FOREIGN KEY (used_by) REFERENCES user(id),
	CONSTRAINT credit_reward_f_account_id FOREIGN KEY (account_id) REFERENCES paid_account(id),
	CONSTRAINT credit_reward_f_account_id_applied_credit FOREIGN KEY (account_id, applied, credit) REFERENCES account_event(account_id, id, credit_change)
);

insert into evolutions(name, description) values('404.sql', 'create credit_code_info and credit_reward');

# --- !Downs
