# SHOEBOX

# --- !Ups

alter table keep_source_attribution modify column attr_json longtext not null;

insert into evolutions(name, description) values ('445.sql', 'make keep_source_attribution.attr_json longtext (to match bookmark.note)');

# --- !Downs
