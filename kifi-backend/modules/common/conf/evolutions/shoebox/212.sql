# SHOEBOX

# --- !Ups

ALTER TABLE bookmark
ADD CONSTRAINT bookmark_f_library_id
FOREIGN KEY (library_id)
REFERENCES library(id)

insert into evolutions (name, description) values('212.sql', 'adding index to libraryId');

# --- !Downs
