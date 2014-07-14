# MAVEN

# --- !Ups

-- MySQL:
-- CREATE TABLE raw_seed_item_sequence (id BIGINT(20) NOT NULL);
-- INSERT INTO raw_seed_item_sequence VALUES (0);
-- H2:
CREATE SEQUENCE raw_seed_item_sequence;

CREATE TABLE raw_seed_item (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    seq bigint(20) NOT NULL,
    uri_id bigint(20) NOT NULL,
    user_id bigint(20) NULL,
    first_kept datetime NOT NULL,
    last_kept datetime NOT NULL,
    last_seen datetime NOT NULL,
    prior_score float NULL,
    times_kept int NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX raw_seed_item_u_uri_id_user_id (uri_id, user_id),
    UNIQUE INDEX raw_seed_item_u_seq_user_id (seq, user_id)
);


CREATE TABLE maven_keep_info (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    uri_id bigint(20) NOT NULL,
    user_id bigint(20) NOT NULL,
    keep_id bigint(20) NOT NULL,
    is_private boolean NOT NULL,
    state varchar(128) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE INDEX maven_keep_info_u_keep_id (keep_id),
    INDEX maven_keep_info_i_uri_id_state (uri_id, state)
);


insert into evolutions (name, description) values('195.sql', 'adding new tables for keep ingestion in maven');


# --- !Downs
