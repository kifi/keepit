# SHOEBOX

# --- !Ups

alter table user_topic modify created_at datetime(6);

INSERT INTO evolutions (name, description) VALUES ('171.sql', 'try microseconds on h2, user_topics db');

# --- !Downs