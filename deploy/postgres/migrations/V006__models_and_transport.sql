-- Current multi-provider model gateway and official transport-signing state.
-- New development deployments only; no legacy model/runtime data is imported.
create table if not exists organization_model_gateway (
 organization_id varchar(128) primary key,
 access_hash varchar(64) not null unique
);
create table if not exists organization_model_providers (
 organization_id varchar(128) primary key,
 revision bigint not null,
 providers_json text not null
);
create table if not exists organization_model_gateway_keys (
 organization_id varchar(128) primary key,
 encrypted_key text not null
);
create table if not exists server_transport_records (
 record_key varchar(128) primary key,
 revision bigint not null,
 encrypted_value text not null
);
