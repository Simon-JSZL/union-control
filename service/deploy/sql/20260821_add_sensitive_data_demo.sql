USE `union_control`;

DROP TABLE IF EXISTS `sensitive_data_demo`;
DROP DATABASE IF EXISTS `sensitive_data_demo`;

CREATE DATABASE `sensitive_data_demo`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `sensitive_data_demo`.`sensitive_data_demo` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `phone_number` VARCHAR(512) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

GRANT SELECT, INSERT ON `sensitive_data_demo`.*
  TO 'union_control_app'@'localhost';

-- AddressBook migration branch rewrites only an unqualified legacy table name,
-- so create both tables in the application datasource's current database.
DROP TABLE IF EXISTS `t_m_announce_address_book_new`;
DROP TABLE IF EXISTS `t_m_announce_address_book`;

CREATE TABLE `t_m_announce_address_book` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `role` VARCHAR(32) NOT NULL,
  `email` VARCHAR(512) NOT NULL,
  `telephone` VARCHAR(512) NOT NULL,
  `mobile_number` VARCHAR(512) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `t_m_announce_address_book_new`
  LIKE `t_m_announce_address_book`;
