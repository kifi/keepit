# SHOEBOX

# --- !Ups

CREATE INDEX password_reset_i_token ON password_reset(token);

insert into evolutions (name, description) values('119.sql', 'add index on password_reset token');

# --- !Downs
