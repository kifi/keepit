# SHOEBOX

# --- !Ups

-- MySQL:
-- CREATE TABLE invitation_sequence (id INT NOT NULL);
-- INSERT INTO invitation_sequence VALUES (0);

-- CREATE TABLE social_connection_sequence (id INT NOT NULL);
-- INSERT INTO social_connection_sequence VALUES (0);

-- CREATE TABLE social_user_info_sequence (id INT NOT NULL);
-- INSERT INTO social_user_info_sequence VALUES (0);
-- H2:
CREATE SEQUENCE invitation_sequence;
CREATE SEQUENCE social_connection_sequence;
CREATE SEQUENCE social_user_info_sequence;
---


INSERT INTO evolutions (name, description) VALUES ('142.sql', 'add sequence number to invitation, social_connection and social_user_info');