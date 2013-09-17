#Shoebox

# --- !Ups
alter table url_pattern_rule
	add column do_not_slide bool NOT NULL;

insert into evolutions (name, description) values('97.sql', 'add do_not_slide to url_pattern_rule');

# --- !Downs