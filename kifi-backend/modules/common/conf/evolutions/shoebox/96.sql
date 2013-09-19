#Shoebox

# --- !Ups

CREATE TABLE url_pattern_rule (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    pattern varchar(2048) NOT NULL,
    example varchar(2048) NULL,
    is_unscrapable bool NOT NULL,
    show_slider bool NOT NULL,
    normalization varchar(32) NULL,
    trusted_domain varchar(256) NULL,

    PRIMARY KEY (id),
    INDEX url_pattern_rule_state (state),
    INDEX url_pattern (pattern)

);

insert into evolutions (name, description) values('96.sql', 'create table url_pattern_rule');

# --- !Downs