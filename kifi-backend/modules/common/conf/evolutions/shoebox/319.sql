
# SHOEBOX

# --- !Ups

CREATE INDEX failed_content_check_i_url2_hash ON failed_content_check(url2_hash);

insert into evolutions (name, description) values('319.sql', 'add failed_content_check_i_url2_hash index');

# --- !Downs
