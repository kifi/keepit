#SHOEBOX

# --- !Ups
alter table url
	add column renormalization_check boolean;

create index url_renormalization_check url(renormalization_check);

insert into evolutions (name, description) values('103.sql', 'add column renormalization_check to url');

# --- !Downs