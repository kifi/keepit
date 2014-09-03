
# SHOEBOX

# --- !Ups

CREATE INDEX library_invite_i_email_address
ON library_invite (email_address);
CREATE INDEX library_invite_i_auth_token
ON library_invite (auth_token);


INSERT INTO evolutions (name, description) VALUES('232.sql', 'add indexes on email addresses & authentication tokens');

# --- !Downs
