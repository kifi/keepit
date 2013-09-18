#Shoebox

# --- !Ups
alter table url_pattern_rule
	drop column show_slider;

insert into evolutions (name, description) values('99.sql', 'drop show_slider from url_pattern_rule');

# --- !Downs