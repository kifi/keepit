# ROVER

# --- !Ups

CREATE INDEX article_info_i_state_last_fetched_at_next_fetch_at ON article_info(state, last_fetched_at,next_fetch_at);

insert into evolutions (name, description) values('334.sql', 'adding index on state-last_fetched_at-next_fetch_at');

# --- !Downs
