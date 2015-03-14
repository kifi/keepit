# ROVER

# --- !Ups
CREATE TABLE if not exists article_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,
    seq bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    url varchar(3072) NOT NULL,
    kind varchar(64) NOT NULL,
    best_version_major int NULL,
    best_version_minor int NULL,
    latest_version_major int NULL,
    latest_version_minor int NULL,
    last_queued_at datetime NULL,
    last_fetched_at datetime NULL,
    next_fetch_at datetime NULL,
    fetch_interval float NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX article_info_u_uri_id_kind (uri_id, kind),
    INDEX article_info_i_next_fetch_at (next_fetch_at),
    INDEX article_info_i_seq (seq)
);

-- MySQL:
-- CREATE TABLE article_info_sequence (id bigint(20) NOT NULL);
-- INSERT INTO article_info_sequence VALUES (0);

CREATE SEQUENCE article_info_sequence;

CREATE TABLE if not exists http_proxy (
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

insert into evolutions (name, description) values('300.sql', 'create article_info, http_proxy table');

# --- !Downs
