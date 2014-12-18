# CURATOR

# --- !Ups

CREATE INDEX recommendation_uri_id_clicked ON uri_recommendation(uri_id, clicked);

INSERT INTO evolutions (name, description) VALUES('274.sql', 'add index on uri_recommendation(uri_id, clicked)');

# --- !Downs

