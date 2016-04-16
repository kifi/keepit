# SHOEBOX

# --- !Ups

ALTER TABLE organization_invite ADD COLUMN reminders_sent SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE organization_invite ADD COLUMN last_reminder_sent_at DATETIME NULL;

INSERT INTO evolutions (name, description) VALUES ('443.sql', 'add reminder columns to organization_invite');

# --- !Downs
