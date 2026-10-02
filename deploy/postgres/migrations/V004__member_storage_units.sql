create table if not exists member_storage_units (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 unit_name varchar(128)  not null,
 format_version integer not null,
 descriptor text not null,
 payload text not null,
 primary key (organization_id,member_id,unit_name),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);
