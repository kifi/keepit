# SHOEBOX

# --- !Ups

<<<<<<< HEAD
-- MySQL:
-- ALTER TABLE domain ADD is_email_provider tinyint(1) DEFAULT 0;
-- UPDATE domain SET is_email_provider = 1 WHERE hostname IN ${ set of email providers };

ALTER TABLE domain ADD is_email_provider tinyint(1) DEFAULT 0;

insert into evolutions (name, description) values('349.sql', 'add is_email_provider to domain');
=======
>>>>>>> evolution update

# --- !Downs
