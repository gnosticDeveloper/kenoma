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
                                     stopped_at         timestamp DEFAULT null
);

-- Bootstrap admin user for integration tests.
-- Password: B00tstr@pPass1
INSERT INTO users (name, last_name, email, username, password, roles)
VALUES (
           'Bootstrap',
           'Admin',
           'admin@bootstrap.local',
           'bootstrap_admin',
           '$2a$10$xI03I5H6IoRGzfpHm4IUGOlQooxsVSVkJM3JMI4QFrJyXvR.6/gw.',
           '{"vassago":["ADMIN","USER"]}'
       ) ON CONFLICT (username) DO NOTHING;