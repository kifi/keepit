# ROVER

# --- !Ups

CREATE INDEX article_info_i_state_next_fetch_at_last_fetching_at ON article_info(state, next_fetch_at, last_fetching_at);

-- for MySQL:
--ALTER TABLE article_info
--    DROP INDEX article_info_i_next_fetch_at,
--    DROP INDEX article_info_i_last_fetched_at,
--    DROP INDEX article_info_i_state_last_fetched_at_next_fetch_at;

-- for H2:
DROP INDEX article_info_i_next_fetch_at;
DROP INDEX article_info_i_last_fetched_at;
DROP INDEX article_info_i_state_last_fetched_at_next_fetch_at;

insert into evolutions (name, description) values('428.sql', 'refactor article_info indices');

# --- !Downs
