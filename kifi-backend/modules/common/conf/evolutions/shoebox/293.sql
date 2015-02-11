# SHOEBX

# --- !Ups

CREATE TABLE twitter_sync_state (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    state varchar(20) NOT NULL,

    twitter_handle varchar(16),
    user_id bigint(20) NULL,
    last_fetched_at datetime NULL,
    library_id bigint(20) NOT NULL,
    max_tweet_id_seen bigint(20) NULL,

    PRIMARY KEY (id)
);



insert into evolutions (name, description) values('293.sql', 'creates twitter_sync_state table');

# --- !Downs
