# CURATOR

# --- !Ups

ALTER TABLE library_recommendation ADD COLUMN delivered INT NOT NULL DEFAULT 0;
ALTER TABLE library_recommendation ADD COLUMN clicked INT NOT NULL DEFAULT 0;
ALTER TABLE library_recommendation ADD COLUMN trashed BOOLEAN NOT NULL DEFAULT false;

INSERT INTO evolutions (name, description) VALUES('275.sql', 'add columns to library_recommendation (delivered,clicked,trashed)');

# --- !Downs

