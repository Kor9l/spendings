-- M7: passkeys (WebAuthn). Схема — из Spring Security (user-entities-schema.sql и
-- user-credentials-schema-postgres.sql), плюс внешний ключ. В бэкап не входят, как и учётная запись.
create table user_entities (
    id           varchar(1000) primary key,          -- случайный идентификатор пользователя для WebAuthn
    name         varchar(100)  not null,             -- логин владельца
    display_name varchar(200)
);

create table user_credentials (
    credential_id                varchar(1000) primary key,
    user_entity_user_id          varchar(1000) not null references user_entities on delete cascade,
    public_key                   bytea         not null,
    signature_count              bigint,
    uv_initialized               boolean,
    backup_eligible              boolean       not null,
    authenticator_transports     varchar(1000),
    public_key_credential_type   varchar(100),
    backup_state                 boolean       not null,
    attestation_object           bytea,
    attestation_client_data_json bytea,
    created                      timestamp,
    last_used                    timestamp,
    label                        varchar(1000) not null
);
create index user_credentials_user on user_credentials (user_entity_user_id);
