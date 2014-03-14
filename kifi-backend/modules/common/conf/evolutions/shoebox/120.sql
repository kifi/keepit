#ABOOK

# --- !Ups

CREATE TABLE econtact (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,

	user_id    bigint(20)    NOT NULL,
	email      varchar(512)  NOT NULL,
	name       varchar(1024),
	first_name varchar(512),
	last_name  varchar(512),

	PRIMARY KEY (id),
	UNIQUE INDEX econtact_i_user_id_email (user_id, email)

) -- DEFAULT CHARSET=utf8mb4
;

insert into evolutions (name, description) values('120.sql', 'adding econtact');

# --- !Downs

DROP TABLE econtact;
