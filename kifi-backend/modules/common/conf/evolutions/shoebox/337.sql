# Shoebox

# --- !Ups

CREATE TABLE handle_ownership (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	last_claimed_at datetime NOT NULL,
	state varchar(20) NOT NULL,
    locked boolean,
    handle varchar(64) NOT NULL,
	organization_id bigint(20) DEFAULT NULL,
	user_id bigint(20) DEFAULT NULL,

	PRIMARY KEY(id),
	UNIQUE INDEX handle_ownership_u_handle (handle),
);

ALTER TABLE user
	ALTER COLUMN username varchar(64) DEFAULT NULL;
ALTER TABLE user
	ALTER COLUMN normalized_username varchar(64) DEFAULT NULL;

insert into evolutions(name, description) values('337.sql', 'add handle_ownership table, make user.username and user.normalized_username optional');

# --- !Downs
