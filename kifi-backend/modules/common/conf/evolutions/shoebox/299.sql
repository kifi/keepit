# SHOEBOX

# --- !Ups

ALTER TABLE social_user_info ADD COLUMN social_hash bigint(20);

INSERT INTO evolutions (name, description) VALUES ('299.sql', 'add social_hash to social_user_info');

# --- !Downs
