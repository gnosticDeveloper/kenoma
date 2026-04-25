Create table credentials (
                             id uuid PRIMARY KEY default gen_random_uuid(),
                             user_name bytea not null ,
                             encrypted_password bytea not null ,
                             modification_lock bool default false,
                             locked_at timestamp,
                             created_at timestamp default current_timestamp,
                             modified_at timestamp default current_timestamp,
                             org_id uuid not null ,
                             service_id uuid not null,
                             db_engine varchar(50) not null
);

create index idx_credentials_org_service on credentials(org_id, service_id);