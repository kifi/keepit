# --- !Ups

--alter TABLE failed_uri_normalization
--	rename to failed_content_check;

--alter TABLE failed_content_check
--	change prep_url_hash url1_hash varchar(26) NOT NULL;

--alter TABLE failed_content_check
--	change mapped_url_hash url2_hash varchar(26) NOT NULL;

--alter TABLE failed_content_check
--	change prep_url url1 varchar(3072) NOT NULL;

--alter TABLE failed_content_check
--	change mapped_url url2 varchar(3072) NOT NULL;

--	insert into evolutions (name, description) values('83.sql', 'modify table and column names in failed_uri_normalization');


alter TABLE failed_uri_normalization
	rename to failed_content_check;

alter TABLE failed_content_check alter column prep_url_hash
    rename to url1_hash;

alter TABLE failed_content_check alter column mapped_url_hash
	rename to url2_hash;

alter TABLE failed_content_check alter column prep_url
	rename to url1;

alter TABLE failed_content_check alter column mapped_url
	rename to url2;

insert into evolutions (name, description) values('83.sql', 'modify table and column names in failed_uri_normalization');


# --- !Downs