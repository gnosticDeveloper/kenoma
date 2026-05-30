CREATE TABLE IF NOT EXISTS organizations (
                                           id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                                           name               varchar(255) NOT NULL,
                                           contact_name       varchar(255) NOT NULL,
                                           contact_email      varchar(255) NOT NULL,
                                           modification_lock  bool DEFAULT false,
                                           locked_at          timestamp,
                                           created_at         timestamp DEFAULT current_timestamp,
                                           modified_at        timestamp DEFAULT current_timestamp,
                                           stopped_at         timestamp default null
);