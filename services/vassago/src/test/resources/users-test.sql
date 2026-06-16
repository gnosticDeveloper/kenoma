CREATE TABLE IF NOT EXISTS users (
                                     id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                     name               varchar(255) NOT NULL,
                                     last_name          varchar(255) NOT NULL,
                                     email              varchar(255) NOT NULL UNIQUE,
                                     username           varchar(255) NOT NULL UNIQUE,
                                     password           varchar(255) NOT NULL,
                                     roles              text NOT NULL DEFAULT '{}',
                                     modification_lock  bool DEFAULT false,
                                     locked_at          timestamp,
                                     created_at         timestamp DEFAULT current_timestamp,
                                     modified_at        timestamp DEFAULT current_timestamp,
                                     stopped_at         timestamp DEFAULT null,
                                     is_ready           bool DEFAULT false
);