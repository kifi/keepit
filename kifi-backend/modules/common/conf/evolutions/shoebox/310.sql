# SHOEBOX

# --- !Ups
alter table url_pattern_rule
    alter column non_sensitive tinyint(1) DEFAULT NULL;

insert into evolutions (name, description) values('310.sql', 'make non_sensitive optional in url_pattern_rule');

# --- !Downs
