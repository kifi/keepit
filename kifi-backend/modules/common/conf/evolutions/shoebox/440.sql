# SHOEBOX

# --- !Ups
CREATE TABLE `keep_tag` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `state` varchar(20) NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `tag` varchar(64) NOT NULL,
  `normalized_tag` varchar(64) NOT NULL,
  `keep_id` bigint(20) NOT NULL,
  `message_id` bigint(20) DEFAULT NULL,
  `user_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `keep_tag_f_user_id` (`user_id`),
  KEY `keep_tag_f_keep_id` (`keep_id`),
  CONSTRAINT `keep_tag_f_user_id` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`),
  CONSTRAINT `keep_tag_f_keep_id` FOREIGN KEY (`keep_id`) REFERENCES `bookmark` (`id`)
) -- DEFAULT CHARSET=utf8mb4
;

insert into evolutions (name, description) values('440.sql', 'creating keep_tag table');

# --- !Downs
