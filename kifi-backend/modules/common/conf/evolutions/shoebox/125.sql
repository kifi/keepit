#ABOOK

# --- !Ups

-- for gmail (re-)import (experimental)
CREATE TABLE oauth2_token (
	id bigint(20) NOT NULL AUTO_INCREMENT,
	created_at datetime NOT NULL,
	updated_at datetime NOT NULL,
	state varchar(20) NOT NULL,

	user_id bigint(20) NOT NULL,
	issuer varchar(40) NOT NULL,
	scope varchar(2048),
    token_type varchar(256),

   	access_token varchar(2048),
    expires_in int,
    refresh_token varchar(2048),
    auto_refresh bool,
    last_refreshed_at datetime,
    id_token varchar(2048),
    raw_token varchar(4096),

	PRIMARY KEY (id)
) -- DEFAULT CHARSET=utf8mb4
;

insert into evolutions (name, description) values('125.sql', 'adding oauth2_token');

# --- !Downs

DROP TABLE oauth2_token;
