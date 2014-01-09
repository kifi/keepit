# SHOEBOX

# --- !Ups

CREATE TABLE feature_waitlist (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,

    email text NOT NULL,
    feature varchar(256) NOT NULL,
    user_agent text NOT NULL,

    PRIMARY KEY (id)
);

insert into evolutions (name, description) values('130.sql', 'adding feature waitlist table');

# --- !Downs