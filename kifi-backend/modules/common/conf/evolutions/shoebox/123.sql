# SHOEBOX

# --- !Ups

CREATE TABLE user_notify_preference (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    user_id bigint(20) NOT NULL,
    name varchar(64) NOT NULL,
    can_send boolean NOT NULL,
    state VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),

    INDEX email_opt_out_i_address (address)
);

insert into evolutions (name, description) values('123.sql', 'adding user_notify_preference table');

# --- !Downs
