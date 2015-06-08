# Shoebox

# --- !Ups

ALTER TABLE bookmark
	ADD COLUMN organization_id bigint(20) DEFAULT NULL;
ALTER TABLE bookmark
    ADD CONSTRAINT `bookmark_f_organization_id` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`);

insert into evolutions(name, description) values('338.sql', 'denormalize organization id onto bookmark to help search know when to show bookmarks');

# --- !Downs