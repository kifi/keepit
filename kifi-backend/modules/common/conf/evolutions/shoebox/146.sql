# SHOEBOX

# --- !Ups

ALTER TABLE user_cred add state varchar(20) NOT NULL DEFAULT 'active' ;

INSERT INTO evolutions (name, description) VALUES ('146.sql', 'add state to user_cred');

# --- !Downs
