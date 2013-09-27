#SHOEBOX

# --- !Ups

CREATE TABLE contact_info (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,

	user_id bigint(20) NOT NULL,
	email varchar(512) NOT NULL,
	origin varchar(128) NOT NULL,
  name varchar(1024),
	first_name varchar(512),
	last_name varchar(512),
	picture_url varchar(2048),
  parent_id bigint(20),                   -- for contacts with multiple emails

	-- KEY (user_id,email),                 -- TODO: decide if we want to de-dup contacts or keep everything
	PRIMARY KEY (id),

	-- CONSTRAINT uc_contact_info_user_id_email UNIQUE (user_id,email),
	CONSTRAINT contact_info_f_user FOREIGN KEY (user_id) REFERENCES user(id)
);

# --- !Downs

DROP TABLE contact_info;
