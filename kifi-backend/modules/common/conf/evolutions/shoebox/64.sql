# --- !Ups

=======
-- MySQl
-- =====
-- alter table scrape_info
--   modify column destination_url varchar(3072);

-- H2
-- =====
alter table scrape_info
  alter column destination_url varchar(3072);

insert into evolutions (name, description) values('64.sql', 'enlarging the destination_url in scrape_info table');

# --- !Downs
