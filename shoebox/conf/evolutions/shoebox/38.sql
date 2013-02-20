# --- !Ups

ALTER TABLE comment_read
  ADD UNIQUE INDEX comment_read__user_id_parent_id (user_id,parent_id);

insert into evolutions (name, description) values('38.sql', 'adding comment_read unique index');

# --- !Downs
