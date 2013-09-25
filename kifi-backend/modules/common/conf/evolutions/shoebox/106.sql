#Shoebox

# --- !Ups

CREATE TABLE http_proxy (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    alias varchar(32) NOT NULL,
    hostname varchar(2048) NOT NULL,
    port int NOT NULL,
    scheme varchar(32) NOT NULL,
    username varchar(2048) NULL,
    password varchar(2048) NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX (alias)
);

alter table url_pattern_rule
    add column use_proxy bigint(20) NULL;

alter table url_pattern_rule
    add CONSTRAINT url_pattern_rule_to_proxy FOREIGN KEY (use_proxy) REFERENCES http_proxy(id);

insert into evolutions (name, description) values('106.sql', 'create table http_proxy and add use_proxy column to url_pattern_rule');

# --- !Downs