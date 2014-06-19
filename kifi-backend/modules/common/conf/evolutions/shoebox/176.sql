# ABOOK

# --- !Ups

-- MySQL:
-- CREATE TABLE email_account_sequence (id INT NOT NULL);
-- INSERT INTO email_account_sequence VALUES (0);
-- H2:
CREATE SEQUENCE email_account_sequence;

CREATE TABLE email_account (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(128) NOT NULL,
    address varchar(512) NOT NULL,
    user_id bigint(20) NULL,
    verified bool NOT NULL,
    seq bigint(20) NOT NULL,

    PRIMARY KEY (id),
    INDEX email_account_i_address (address),
    INDEX email_account_i_seq (seq)
);

insert into evolutions (name, description) values('176.sql', 'create email_account table');

# --- !Downs
