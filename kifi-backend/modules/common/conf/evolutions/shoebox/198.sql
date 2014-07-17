# SHOEBOX

# --- !Ups

ALTER TABLE bookmark ADD COLUMN library_id bigint(20) NULL;

-- ALTER TABLE bookmark ADD CONSTRAINT bookmark_f_library_id FOREIGN KEY (library_id) REFERENCES library(id);

-- ALTER TABLE bookmark ADD INDEX bookmark_i_library_id (library_id);


ALTER TABLE user ADD COLUMN username varchar(64) NULL;

ALTER TABLE user ADD INDEX user_i_username (username);



ALTER TABLE library ADD COLUMN seq bigint(20) NOT NULL;

ALTER TABLE library ADD INDEX library_i_seq (seq);


ALTER TABLE library_member RENAME TO library_membership;

ALTER TABLE library_membership ADD COLUMN seq bigint(20) NOT NULL;

ALTER TABLE library_membership ADD INDEX library_membership_i_seq (seq);


insert into evolutions (name, description) values('198.sql', 'library_id column, username, seqs, lots of indexes');

# --- !Downs
