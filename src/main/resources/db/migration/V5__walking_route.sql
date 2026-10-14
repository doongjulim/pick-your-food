create table walking_route (
	route_key varchar(400) primary key,
	meters integer not null,
	seconds integer not null,
	path varchar(16000) not null,
	created_at timestamp not null
);

create index walking_route_created on walking_route (created_at);
