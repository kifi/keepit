# CURATOR

# --- !Ups

-- MySQL:
-- CREATE TABLE curator_library_info_sequence (id bigint(20) NOT NULL);
-- INSERT INTO curator_library_info_sequence VALUES (0);
-- H2:
CREATE SEQUENCE curator_library_info_sequence;

ALTER TABLE curator_library_info ADD COLUMN seq bigint(20) NOT NULL;
CREATE INDEX curator_library_info_i_seq on curator_library_info(seq);

insert into evolutions (name, description) values('266.sql', 'add sequence number to curator_library_info');

# --- !Downs

