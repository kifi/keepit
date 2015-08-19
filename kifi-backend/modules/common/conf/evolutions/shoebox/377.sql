# SHOEBOX

# --- !Ups

ALTER TABLE `user_cred` DROP COLUMN `login_name`;
ALTER TABLE `user_cred` DROP COLUMN `provider`;
ALTER TABLE `user_cred` DROP COLUMN `salt`;

insert into evolutions (name, description) values('377.sql', 'drop columns user_cred.{login_name, provider, salt}');

# --- !Downs
