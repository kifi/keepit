# CURATOR

# --- !Ups

ALTER TABLE curator_library_info ADD COLUMN name VARCHAR(256) NOT NULL DEFAULT '';
ALTER TABLE curator_library_info ADD COLUMN description_length SMALLINT NOT NULL DEFAULT 0;

INSERT INTO evolutions (name, description) VALUES('280.sql', 'add column to curator_library_info (name,description_length)');

# --- !Downs
