# SHOEBOX

# --- !Ups

ALTER TABLE twitter_sync_state ADD COLUMN min_tweet_id_seen bigint(20) NULL;

INSERT INTO evolutions (name, description) VALUES ('316.sql', 'add min_tweet_id_seen to twitter_sync_state');

# --- !Downs
