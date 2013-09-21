#SHOEBOX

# --- !Ups

CREATE TABLE user_cred (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,

	user_id bigint(20),
	login_name varchar(512) NOT NULL,
	provider varchar(256) NOT NULL,
	salt varchar(256) NOT NULL,
	credentials varchar(512) NOT NULL,

	PRIMARY KEY (id),

	CONSTRAINT user_cred_f_user FOREIGN KEY (user_id) REFERENCES user(id)
);

# --- !Downs

DROP TABLE user_cred;