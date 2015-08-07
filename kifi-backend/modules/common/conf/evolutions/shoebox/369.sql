# SHOEBOX

# --- !Ups

create table notification (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  user_id bigint(20) NOT NULL,
  last_checked datetime NOT NULL,
  kind VARCHAR(32) NOT NULL,

  PRIMARY KEY(id)
);

create table notification_item (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  created_at datetime NOT NULL,
  updated_at datetime NOT NULL,
  notification_id bigint(20) NOT NULL,
  kind VARCHAR(32) NOT NULL,
  event LONGTEXT,

  PRIMARY KEY(id),
  CONSTRAINT notification_item_notification_id FOREIGN KEY (notification_id) REFERENCES notification(id)
);

insert into evolutions (name, description) values('369.sql', 'Add notification and notification_item tables');

# --- !Downs
