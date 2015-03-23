# ELIZA

# --- !Ups

CREATE TABLE device (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    user_id bigint(20) NOT NULL,
    token varchar(64) NOT NULL,
    device_type varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    PRIMARY KEY (id)
);

insert into evolutions (name, description) values('71.sql', 'adding device table');

# --- !Downs
