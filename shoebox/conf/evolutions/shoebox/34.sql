# --- !Ups

CREATE INDEX manual_sensitive_index ON domain (state, manual_sensitive);

INSERT INTO evolutions (name, description) VALUES ('34.sql', 'index on manual_sensitive');

# --- !Downs
