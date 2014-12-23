# SHOEBOX

# --- !Ups

drop index library_membership_i_visible;
alter table library_membership drop column visibility;

create index library_membership_i_listed on library_membership(listed);

INSERT INTO evolutions (name, description) VALUES('278.sql', 'delete visibility column from library_membership');


# --- !Downs


