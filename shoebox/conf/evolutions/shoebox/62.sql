# --- !Ups

ALTER TABLE collection ADD last_kept_to DATETIME;
CREATE INDEX collection_i_last_kept_to ON collection(last_kept_to);

ALTER TABLE collection DROP CONSTRAINT collection_u_user_id_name;
CREATE INDEX collection_i_user_id_name ON collection (user_id, name, state);

INSERT INTO evolutions (name, description) VALUES ('62.sql', 'add last_kept_to to collection');

# --- !Downs
