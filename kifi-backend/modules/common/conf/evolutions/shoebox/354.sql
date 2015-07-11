# Shoebox

# --- !Ups

alter table url_pattern_rule
    drop column is_unscrapable;

alter table url_pattern_rule
    drop column use_proxy;

drop table http_proxy;

insert into evolutions(name, description) values('354.sql', 'remove old http_proxy table, take out some columns from old url_pattern_rule');

# --- !Downs
