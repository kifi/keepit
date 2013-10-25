#ABOOK

# --- !Ups

CREATE TABLE abook_info (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,

	user_id bigint(20) NOT NULL,
	origin varchar(128) NOT NULL,
	owner_id varchar(256),
	owner_email varchar(512),
	raw_info_loc varchar(512),

	PRIMARY KEY (id)
) -- DEFAULT CHARSET=utf8mb4
;

# --- !Downs

DROP TABLE abook;
