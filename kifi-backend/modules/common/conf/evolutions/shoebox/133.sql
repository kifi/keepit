# SHOEBOX

# --- !Ups

alter table scrape_info
  add column worker_id INTEGER; 

insert into evolutions (name, description) values('133.sql', 'worker (zk) id');

# --- !Downs

