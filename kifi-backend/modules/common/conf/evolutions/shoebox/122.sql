# SHOEBOX

# --- !Ups

CREATE TABLE email_opt_out (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    address varchar(512) NOT NULL,
    category varchar(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),

    INDEX email_opt_out_i_address (address)
);

insert into evolutions (name, description) values('122.sql', 'adding email_opt_out table');

# --- !Downs
