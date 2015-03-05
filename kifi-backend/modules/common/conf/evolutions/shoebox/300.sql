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
    major int NULL,
    minor int NULL,
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

insert into evolutions (name, description) values('300.sql', 'create article info table');

# --- !Downs
