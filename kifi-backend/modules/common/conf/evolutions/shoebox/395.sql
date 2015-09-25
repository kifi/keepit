# ELIZA

# --- !Ups

ALTER TABLE notification ADD COLUMN backfilled_for bigint(20) DEFAULT NULL;
ALTER TABLE notification add constraint `notification_backfilled_for` FOREIGN KEY (backfilled_for) REFERENCES user_thread(id);
ALTER TABLE notification add index `notification_kind_idx` (kind);

insert into evolutions (name, description) values('395.sql', 'Add backfilled_for in notification, temporarily');


# --- !Downs
