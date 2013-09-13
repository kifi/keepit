#Shoebox

# --- !Ups
alter table domain
	delete column normalization_scheme

CREATE TABLE url_pattern_rule (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    pattern varchar(256) NOT NULL,
    is_unscrapable bool NOT NULL,
    normalization varchar(32) NULL,
    trusted_domain varchar(256) NULL,

    PRIMARY KEY (id),
    INDEX url_pattern_rule_state (state)
    INDEX url_pattern (pattern)

);

insert into evolutions (name, description) values('95.sql', 'delete normalization_scheme from domain and create table url_pattern_rule');

# --- !Downs