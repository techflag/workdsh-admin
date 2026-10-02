
create table if not exists member_secrets (
 organization_id varchar(36) not null references organizations(id),
 member_id varchar(36) not null references members(id),
 credential_ref varchar(128) not null,
 encrypted_value text not null,
 primary key (organization_id,member_id,credential_ref)
);
