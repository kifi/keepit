# SHOEBOX

# --- !Ups

ALTER TABLE library_invite ADD COLUMN reminders_sent SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE library_invite ADD COLUMN last_reminder_sent_at DATETIME NULL;

INSERT INTO evolutions (name, description) VALUES ('432.sql', 'add reminder columns to library_invite');

# --- !Downs
