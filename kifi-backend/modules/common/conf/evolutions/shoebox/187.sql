# SHOEBOX

# --- !Ups
CREATE INDEX image_info_i_seq ON image_info(seq);
CREATE INDEX page_info_i_seq ON page_info(seq);

insert into evolutions (name, description) values('187.sql', 'add index in seq of image_info and page_info');

# --- !Downs
