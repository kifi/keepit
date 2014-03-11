#ABOOK

# --- !Ups

CREATE TABLE contact (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,

	user_id bigint(20) NOT NULL,
	abook_id bigint(20) NOT NULL,
	email varchar(512)  NOT NULL,
	email_list varchar(4096),
	origin varchar(128) NOT NULL,
  	name varchar(1024),
	first_name varchar(512),
	last_name varchar(512),
	picture_url varchar(2048),

	PRIMARY KEY (id)

	-- CONSTRAINT contact_abook_id FOREIGN KEY (abook_id) REFERENCES abook_info(id)
) -- DEFAULT CHARSET=utf8mb4
;

insert into evolutions (name, description) values('115.sql', 'adding contact');

# --- !Downs

DROP TABLE contact;
