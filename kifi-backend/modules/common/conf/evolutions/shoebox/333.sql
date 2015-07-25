#Shoebox

# --- !Ups

drop table scrape_info;
drop table image_info;
drop table page_info;
drop table duplicate_document;

insert into evolutions (name, description) values('333.sql', 'drop tables scrape_info, image_info, page_info, duplicate_document');

# --- !Downs
