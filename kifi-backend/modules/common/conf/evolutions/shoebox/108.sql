#ABOOK

# --- !Ups

CREATE TABLE abook (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,

	user_id bigint(20) NOT NULL,
	origin varchar(128) NOT NULL,
	raw_info_loc varchar(512),

	PRIMARY KEY (id)

	-- CONSTRAINT abook_f_user FOREIGN KEY (user_id) REFERENCES user(id) -- user table in shoebox db; no links
);

# --- !Downs

DROP TABLE abook;
