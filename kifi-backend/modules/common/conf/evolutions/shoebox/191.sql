<<<<<<< HEAD
# ABOOK

# --- !Ups

-- ALTER TABLE email_account_sequence MODIFY id bigint(20);

ALTER TABLE econtact ADD COLUMN abook_id bigint(20) NULL;
ALTER TABLE econtact ADD COLUMN email_account_id bigint(20) NULL;

ALTER TABLE econtact DROP CONSTRAINT econtact_i_user_id_email;
-- DROP INDEX econtact_i_user_id_email ON econtact;

CREATE UNIQUE INDEX econtact_i_abook_id_email_account_id ON econtact(abook_id, email_account_id);
CREATE INDEX econtact_i_user_id ON econtact(user_id);

insert into evolutions (name, description) values('191.sql', 'add abook_id, email_account_id to econtact');
=======
# SHOEBOX

# --- !Ups

CREATE TABLE library (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,
    external_id varchar(36) NOT NULL,
    state varchar(20) NOT NULL,

    name varchar(256) NOT NULL,
    ownerId bigint(20) NOT NULL,
    description varchar(512),
    privacy varchar(20),
    tokens varchar(512) NOT NULL,

    PRIMARY KEY (id),
    FOREIGN KEY (ownerId) REFERENCES user(id)
);

CREATE TABLE library_member (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    libraryId bigint(20) NOT NULL,
    userId bigint(20) NOT NULL,
    permission varchar(20) NOT NULL,
    state varchar(20) NOT NULL,
    created_at datetime NOT NULL,
    updated_at datetime NOT NULL,

    PRIMARY KEY (id),
    FOREIGN KEY (userId) REFERENCES user(id),
    FOREIGN KEY (libraryId) REFERENCES library(id)
);

insert into evolutions (name, description) values('191.sql', 'adding new tables for library keeps');

>>>>>>> d044494a56c6261230d15fd681cc12c94eab5c3d

# --- !Downs
