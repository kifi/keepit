# ROVER

# --- !Ups
ALTER TABLE article_info ADD COLUMN url_hash varchar(26) NOT NULL AFTER url;
CREATE UNIQUE INDEX article_info_u_url_hash_kind on article_info(url_hash, kind);

insert into evolutions (name, description) values('330.sql', 'add article_info.url_hash, article_info_u_url_hash_kind index');

# --- !Downs
