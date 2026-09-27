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
create table if not exists runtime_credentials (
  token_hash varchar(64) primary key,
  member_id varchar(36) not null references members(id),
  created_at timestamp not null,
  active boolean not null
);
create table if not exists dsh_launch_tickets (
  token_hash varchar(64) primary key,
  member_id varchar(36) not null references members(id),
  auth_token_hash varchar(64) not null,
  expires_at timestamp not null,
  consumed_at timestamp
);
alter table dsh_launch_tickets add column if not exists auth_token_hash varchar(64) default '' not null;
create table if not exists orders (
  id varchar(36) primary key,
  organization_id varchar(36) not null references organizations(id),
  creator_id varchar(36) not null references members(id),
  customer_name varchar(160) not null,
  source_type varchar(16) not null,
  source_name varchar(255) not null,
  status varchar(24) not null,
  reviewer_id varchar(36),
  revision integer not null,
  created_at timestamp not null
);
create table if not exists order_lines (
  id varchar(36) primary key,
  order_id varchar(36) not null references orders(id),
  customer_sku varchar(160) not null,
  customer_name varchar(255) not null,
  quantity integer not null,
  internal_sku varchar(160),
  match_status varchar(24) not null
);
alter table order_lines add column if not exists customer_reference varchar(160) default '' not null;
create table if not exists order_sources (
  id varchar(36) primary key,
  order_id varchar(36) not null references orders(id),
  uploaded_by varchar(36) not null references members(id),
  file_name varchar(255) not null,
  source_type varchar(16) not null,
  media_type varchar(100) not null,
  size_bytes bigint not null,
  sha256 varchar(64) not null,
  content bytea not null,
  created_at timestamp not null
);
alter table order_lines add column if not exists source_id varchar(36) references order_sources(id);
alter table order_lines add column if not exists source_locator varchar(100);
create unique index if not exists order_line_origin on order_lines(order_id,source_id,source_locator);
create table if not exists order_access (
  order_id varchar(36) not null references orders(id),
  member_id varchar(36) not null references members(id),
  access_role varchar(16) not null,
  primary key(order_id, member_id)
);
create table if not exists review_events (
  id varchar(36) primary key,
  order_id varchar(36) not null references orders(id),
  actor_id varchar(36) not null references members(id),
  action varchar(24) not null,
  comment varchar(1000),
  created_at timestamp not null
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
