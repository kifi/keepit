# CORTEX

# --- !Ups
alter table uri_lda_topic add column num_words smallint unsigned default 0;

insert into evolutions (name, description) values('206.sql', 'add num_words to uri_lda_topic');

# --- !Downs
