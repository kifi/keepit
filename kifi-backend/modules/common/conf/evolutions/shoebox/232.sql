# CURATOR

# --- !Ups

CREATE TABLE if not exists public_feed (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    uri_id bigint(20) NOT NULL,
    public_master_score float(10) NOT NULL,
    public_all_scores text NOT NULL,
    PRIMARY KEY (id),

    UNIQUE INDEX public_feed_u_uri_id (uri_id),
    INDEX public_feed_id_master_score (all_master_score)
);

insert into evolutions (name, description) values('232.sql', 'create public feed table');

# --- !Downs
