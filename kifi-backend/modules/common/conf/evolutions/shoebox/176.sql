#Shoebox

# --- !Ups

# ALTER TABLE user
#  DROP FOREIGN KEY user_f_email

ALTER TABLE user
	DROP COLUMN primary_email_id;

insert into evolutions (name, description) values('176.sql', 'drop primary_email_id column from user table');

# --- !Downs
