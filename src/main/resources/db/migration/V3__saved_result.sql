create table saved_result (
	id varchar(22) primary key,
	account_id bigint not null references account (id),
	title varchar(100) not null,
	food_name varchar(100) not null,
	origin_name varchar(200),
	payload varchar(65536) not null,
	created_at timestamp not null
);

create index saved_result_account on saved_result (account_id, created_at);
