# ROVER

# --- !Ups
alter table article_info
    add column domain varchar(512) NULL;
CREATE INDEX article_info_i_last_fetched_at ON article_info(last_fetched_at);

insert into evolutions (name, description) values('314.sql', 'add domain column to article_info, article_info_i_last_fetched_at index');

# --- !Downs
