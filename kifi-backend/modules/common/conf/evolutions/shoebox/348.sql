# ROVER

# --- !Ups

ALTER TABLE article_image ADD COLUMN url varchar(3072) NOT NULL DEFAULT '' AFTER uri_id;
ALTER TABLE article_image ADD COLUMN url_hash varchar(26) NOT NULL DEFAULT '' AFTER url;

-- These constraints leverage MySQL's property of not enforcing constraints involving null columns. H2 does...
--ALTER TABLE article_image
-- CREATE UNIQUE INDEX article_image_u_uri_hash_kind_image_hash on article_image(uri_hash, kind, image_hash)
-- CONSTRAINT article_image_f_article_info_url_hash FOREIGN KEY (url_hash, kind) REFERENCES article_info(url_hash, kind)

insert into evolutions (name, description) values('348.sql', 'add url, url_hash, constraints to article_image');

# --- !Downs
