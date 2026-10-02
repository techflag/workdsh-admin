create table if not exists organizations (
  id varchar(36) primary key,
  name varchar(160) not null
);
create table if not exists members (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  email varchar(254) not null unique,
  display_name varchar(120) not null,
  role varchar(16) not null,
  password_hash varchar(100) not null,
  must_change_password boolean not null,
  active boolean not null,
  revision integer not null,
 foreign key (organization_id) references organizations(id)
);
create table if not exists auth_sessions (
  token_hash varchar(64) primary key,
  member_id varchar(36) not null,
  expires_at timestamp not null,
 foreign key (member_id) references members(id)
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
  organization_id varchar(36) not null,
  sender_id varchar(36) not null,
  recipient_id varchar(36) not null,
  request_key varchar(128) not null,
  summary varchar(2000) not null,
  status varchar(16) not null,
  resolution varchar(2000),
  created_at timestamp not null,
  completed_at timestamp,
  unique(sender_id, request_key),
 foreign key (organization_id) references organizations(id),
 foreign key (sender_id) references members(id),
 foreign key (recipient_id) references members(id)
);
create index collaboration_handoff_inbox on collaboration_handoffs(organization_id,recipient_id,status,created_at);
create table if not exists collaboration_messages (
  id varchar(36) primary key,
  handoff_id varchar(36) not null,
  organization_id varchar(36) not null,
  author_id varchar(36) not null,
  request_key varchar(128) not null,
  content varchar(2000) not null,
  created_at timestamp not null,
  unique(handoff_id,author_id,request_key),
 foreign key (handoff_id) references collaboration_handoffs(id),
 foreign key (organization_id) references organizations(id),
 foreign key (author_id) references members(id)
);
create index collaboration_messages_thread on collaboration_messages(handoff_id,created_at);

create table if not exists collaboration_notifications (
  id varchar(36) primary key,
  organization_id varchar(36) not null,
  recipient_id varchar(36) not null,
  author_id varchar(36) not null,
  handoff_id varchar(36) not null,
  kind varchar(24) not null,
  preview varchar(240) not null,
  created_at timestamp not null,
  read_at timestamp,
 foreign key (organization_id) references organizations(id),
 foreign key (recipient_id) references members(id),
 foreign key (author_id) references members(id),
 foreign key (handoff_id) references collaboration_handoffs(id)
);
create index collaboration_notifications_member on collaboration_notifications(organization_id,recipient_id,read_at,created_at);

create table if not exists collaboration_materials (
  handoff_id varchar(36) primary key,
  payload longtext not null,
 foreign key (handoff_id) references collaboration_handoffs(id)
);

create table if not exists departments (
 id varchar(36) primary key,
 organization_id varchar(36) not null,
 parent_id varchar(36),
 name varchar(64) not null,
 revision integer not null default 1,
 foreign key (organization_id) references organizations(id)
);
create table if not exists member_profiles (
 member_id varchar(36) primary key,
 organization_id varchar(36) not null,
 department_id varchar(36),
 username varchar(64) unique,
 employee_number varchar(64),
 job_title varchar(64),
 foreign key (member_id) references members(id),
 foreign key (organization_id) references organizations(id)
);
create table if not exists organization_policies (
 organization_id varchar(36) primary key,
 directory_scope varchar(24) not null default 'ORGANIZATION',
 sharing_enabled boolean not null default true,
 revision integer not null default 1,
 foreign key (organization_id) references organizations(id)
);

create table if not exists member_secrets (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 credential_ref varchar(128) character set ascii collate ascii_bin not null,
 encrypted_value text not null,
 primary key (organization_id,member_id,credential_ref),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);

create table if not exists member_secret_records (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 record_key varchar(255) character set ascii collate ascii_bin not null,
 revision bigint not null,
 kind varchar(16),
 encrypted_value longtext,
 primary key (organization_id,member_id,record_key),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);

create table if not exists member_storage_units (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 unit_name varchar(128) character set ascii collate ascii_bin not null,
 format_version integer not null,
 descriptor longtext not null,
 payload longtext not null,
 primary key (organization_id,member_id,unit_name),
 foreign key (organization_id) references organizations(id),
 foreign key (member_id) references members(id)
);

create table if not exists member_sessions (
 organization_id varchar(36) not null,
 member_id varchar(36) not null,
 session_id varchar(128) character set ascii collate ascii_bin not null,
 header_json longtext not null,
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
 session_id varchar(128) character set ascii collate ascii_bin not null,
 event_seq bigint not null,
 event_json longtext not null,
 primary key (organization_id,member_id,session_id,event_seq),
 foreign key (organization_id,member_id,session_id) references member_sessions(organization_id,member_id,session_id)
);

create table if not exists server_transport_records (
 record_key varchar(128) primary key,
 revision bigint not null,
 encrypted_value longtext not null
);


create table if not exists organization_model_gateway (organization_id varchar(128) primary key, access_hash varchar(64) not null unique);

create table if not exists organization_model_providers (organization_id varchar(128) primary key, revision bigint not null, providers_json text not null);

create table if not exists organization_model_gateway_keys (organization_id varchar(128) primary key, encrypted_key text not null);
