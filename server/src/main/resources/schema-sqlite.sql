create table if not exists organizations (
  id varchar(36) primary key,
  name varchar(160) not null
);
create table if not exists members (
  id varchar(36) primary key,
  organization_id varchar(36) not null references organizations(id),
  email varchar(254) not null unique,
  display_name varchar(120) not null,
  role varchar(16) not null,
  password_hash varchar(100) not null,
  must_change_password boolean not null,
  active boolean not null,
  revision integer not null
);
create table if not exists auth_sessions (
  token_hash varchar(64) primary key,
  member_id varchar(36) not null references members(id),
  expires_at timestamp not null
);
create table if not exists audit_events (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  actor_id varchar(36) not null,
  action varchar(80) not null,
  target_id varchar(36),
  occurred_at timestamp not null
);
create table if not exists collaboration_handoffs (
  id varchar(36) primary key,
  organization_id varchar(36) not null references organizations(id),
  sender_id varchar(36) not null references members(id),
  recipient_id varchar(36) not null references members(id),
  request_key varchar(128) not null,
  summary varchar(2000) not null,
  status varchar(16) not null,
  resolution varchar(2000),
  created_at timestamp not null,
  completed_at timestamp,
  unique(sender_id, request_key)
);
create index if not exists collaboration_handoff_inbox on collaboration_handoffs(organization_id,recipient_id,status,created_at);
create table if not exists collaboration_messages (
  id varchar(36) primary key,
  handoff_id varchar(36) not null references collaboration_handoffs(id),
  organization_id varchar(36) not null references organizations(id),
  author_id varchar(36) not null references members(id),
  request_key varchar(128) not null,
  content varchar(2000) not null,
  created_at timestamp not null,
  unique(handoff_id,author_id,request_key)
);
create index if not exists collaboration_messages_thread on collaboration_messages(handoff_id,created_at);

create table if not exists collaboration_notifications (
  id varchar(36) primary key,
  organization_id varchar(36) not null references organizations(id),
  recipient_id varchar(36) not null references members(id),
  author_id varchar(36) not null references members(id),
  handoff_id varchar(36) not null references collaboration_handoffs(id),
  kind varchar(24) not null,
  preview varchar(240) not null,
  created_at timestamp not null,
  read_at timestamp
);
create index if not exists collaboration_notifications_member on collaboration_notifications(organization_id,recipient_id,read_at,created_at);

create table if not exists collaboration_materials (
  handoff_id varchar(36) primary key references collaboration_handoffs(id),
  payload text not null
);

create table if not exists departments (
 id varchar(36) primary key,
 organization_id varchar(36) not null references organizations(id),
 parent_id varchar(36),
 name varchar(64) not null,
 revision integer not null default 1
);
create table if not exists member_profiles (
 member_id varchar(36) primary key references members(id),
 organization_id varchar(36) not null references organizations(id),
 department_id varchar(36),
 username varchar(64) unique,
 employee_number varchar(64),
 job_title varchar(64)
);
create table if not exists organization_policies (
 organization_id varchar(36) primary key references organizations(id),
 directory_scope varchar(24) not null default 'ORGANIZATION',
 sharing_enabled boolean not null default true,
 revision integer not null default 1
);

create table if not exists member_secrets (
 organization_id varchar(36) not null references organizations(id),
 member_id varchar(36) not null references members(id),
 credential_ref varchar(128) not null,
 encrypted_value text not null,
 primary key (organization_id,member_id,credential_ref)
);

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

create table if not exists member_sessions (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 session_id varchar(128)  not null,
 header_json text not null,
 inherited_count bigint not null,
 event_count bigint not null default 0,
 writer_id varchar(36),
 writer_auth varchar(64),
 writer_expires bigint not null default 0,
 primary key (organization_id,member_id,session_id),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id),
 foreign key (writer_auth) references auth_sessions(token_hash) on delete set null
);
create table if not exists member_session_events (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 session_id varchar(128)  not null,
 event_seq bigint not null,
 event_json text not null,
 primary key (organization_id,member_id,session_id,event_seq),
 foreign key (organization_id,member_id,session_id) references member_sessions(organization_id,member_id,session_id)
);

create table if not exists server_transport_records (
 record_key varchar(128) primary key,
 revision bigint not null,
 encrypted_value text not null
);


create table if not exists organization_model_gateway (organization_id varchar(128) primary key, access_hash varchar(64) not null unique);

create table if not exists organization_model_providers (organization_id varchar(128) primary key, revision bigint not null, providers_json text not null);

create table if not exists organization_model_gateway_keys (organization_id varchar(128) primary key, encrypted_key text not null);

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
