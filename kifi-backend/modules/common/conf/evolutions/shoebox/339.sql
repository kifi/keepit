# Shoebox

# --- !Ups

CREATE TABLE user_ip_addresses (
	id bigint(20) NOT NULL AUTO_INCREMENT UNIQUE,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,
	user_id bigint(20) NOT NULL,
	ip_address VARCHAR(15) NOT NULL,
	agent_type VARCHAR(20) NOT NULL,

	PRIMARY KEY(id),
	CONSTRAINT ip_address_f_user FOREIGN KEY (user_id) REFERENCES user(id)
);

insert into evolutions(name, description) values('339.sql', 'add user ip address table');

# --- !Downs
