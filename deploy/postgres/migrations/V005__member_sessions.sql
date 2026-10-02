
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
