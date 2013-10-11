#ABOOK

# --- !Ups

CREATE TABLE contact_info (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,

	user_id bigint(20) NOT NULL,
	abook_id bigint(20) NOT NULL,
	email varchar(512) character set utf8 NOT NULL,
	origin varchar(128) NOT NULL,
  	name varchar(1024) character set utf8,
	first_name varchar(512) character set utf8,
	last_name varchar(512) character set utf8,
	picture_url varchar(2048),
  	parent_id bigint(20),                   -- for contacts with multiple emails

	PRIMARY KEY (id)

	-- CONSTRAINT contact_info_abook_id FOREIGN KEY (abook_id) REFERENCES abook_info(id)
);

# --- !Downs

DROP TABLE contact_info;
