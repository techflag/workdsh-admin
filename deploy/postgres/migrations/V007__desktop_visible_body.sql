
-- Client-submitted Desktop visible text has its own immutable namespace.
create table if not exists member_desktop_body_sessions (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 client_session_id varchar(128) not null,
 device_id varchar(128) not null,
 server_session_id varchar(128) not null,
 created_at bigint not null,
 revision bigint not null default 0,
 record_count bigint not null default 0,
 deleted_at bigint not null default 0,
 primary key (organization_id,member_id,client_session_id),
 unique (organization_id,member_id,server_session_id),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);
create table if not exists member_desktop_body_records (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 client_session_id varchar(128) not null,
 record_seq bigint not null,
 record_id varchar(128) not null,
 role varchar(16) not null,
 body_text text not null,
 received_at bigint not null,
 primary key (organization_id,member_id,client_session_id,record_seq),
 unique (organization_id,member_id,client_session_id,record_id),
 foreign key (organization_id,member_id,client_session_id) references member_desktop_body_sessions(organization_id,member_id,client_session_id)
);
create table if not exists member_desktop_body_receipts (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 client_session_id varchar(128) not null,
 request_id varchar(128) not null,
 revision bigint not null,
 payload_hash varchar(64) not null,
 record_count bigint not null,
 primary key (organization_id,member_id,client_session_id,request_id),
 unique (organization_id,member_id,client_session_id,revision),
 foreign key (organization_id,member_id,client_session_id) references member_desktop_body_sessions(organization_id,member_id,client_session_id)
);
