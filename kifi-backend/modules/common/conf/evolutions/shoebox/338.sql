# Shoebox

# --- !Ups

ALTER TABLE handle_ownership
	ADD CONSTRAINT `handle_ownership_f_user` FOREIGN KEY (`user_id`) REFERENCES user(`id`)

ALTER TABLE user
   ADD CONSTRAINT user_f_handle_ownership FOREIGN KEY (normalized_username, id) REFERENCES handle_ownership(handle, user_id);

ALTER TABLE handle_ownership
	ADD CONSTRAINT `handle_ownership_f_organization` FOREIGN KEY (`organization_id`) REFERENCES organization(`id`)

ALTER TABLE organization
   ADD CONSTRAINT organization_f_handle_ownership FOREIGN KEY (normalized_handle, id) REFERENCES handle_ownership(handle, organization_id);

insert into evolutions(name, description) values('338.sql', 'add handle integrity constraints');

# --- !Downs
