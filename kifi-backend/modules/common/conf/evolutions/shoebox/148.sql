#SHOEBOX

# --- !Ups
alter table url_pattern_rule
  add column non_sensitive Boolean NULL;

insert into evolutions (name, description) values('148.sql', 'add non_sensitive column to url_pattern_rule');


# --- !Downs