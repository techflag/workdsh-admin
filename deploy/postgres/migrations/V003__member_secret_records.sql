
create table if not exists member_secret_records (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 record_key varchar(255) not null,
 revision bigint not null,
 kind varchar(16),
 encrypted_value text,
 primary key (organization_id,member_id,record_key),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);
