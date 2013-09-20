#Shoebox

# --- !Ups
alter table url_pattern_rule
	drop column do_not_slide;

alter table domain
	drop column normalization_scheme;

drop table unscrapable;

insert into evolutions (name, description) values('100.sql', 'drop do_not_slide column, normalization_scheme column and unscrapable table');

# --- !Downs