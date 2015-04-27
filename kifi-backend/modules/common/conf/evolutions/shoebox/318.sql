# ROVER

# --- !Ups
alter table article_info
    drop column domain;

insert into evolutions (name, description) values('318.sql', 'remove domain column from article_info');

# --- !Downs