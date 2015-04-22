# CORTEX

# --- !Ups

create index cortex_keep_i_uri on cortex_keep(uri_id);

insert into evolutions (name, description) values('323.sql', 'add index on cortex_keep and lda_related_library');

# --- !Downs