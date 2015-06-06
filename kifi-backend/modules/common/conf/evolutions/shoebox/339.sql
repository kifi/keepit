# Shoebox

# --- !Ups

CREATE TABLE user_ip_addresses (
	id bigint(20) NOT NULL AUTO_INCREMENT UNIQUE,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	user_id bigint(20) DEFAULT NULL,
	ip_address VARCHAR(15) DEFAULT NULL,
	agent_type VARCHAR(20) DEFAULT NULL,

	PRIMARY KEY(id)
);

insert into evolutions(name, description) values('339.sql', 'add user ip address table');

# --- !Downs
