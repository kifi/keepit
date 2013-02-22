# --- !Ups

ALTER TABLE comment_read
  ADD UNIQUE INDEX comment_read__user_id_parent_id (user_id, parent_id);

-- for MySQL:
-- ALTER TABLE comment_read
--   DROP INDEX comment_read_i_message,
--   DROP INDEX comment_read_i_comment;

-- for H2:
DROP INDEX comment_read_i_message;
DROP INDEX comment_read_i_comment;

insert into evolutions (name, description) values('38.sql', 'adding comment_read unique index');

# --- !Downs
