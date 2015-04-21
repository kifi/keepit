# ROVER

# --- !Ups
ALTER TABLE article_info add column last_fetching_at datetime NULL;
ALTER TABLE article_info drop column last_queued_at;

insert into evolutions (name, description) values('321.sql', 'drop last_queued_at, add last_fetching_at columns on article_info');

# --- !Downs
