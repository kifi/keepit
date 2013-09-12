#Shoebox

# --- !Ups
alter table domain
	add column normalization_scheme varchar(32) NULL;

insert into evolutions (name, description) values('95.sql', 'add normalization_scheme to domain');

# --- !Downs