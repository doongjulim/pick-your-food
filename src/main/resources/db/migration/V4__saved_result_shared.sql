-- every result starts shared, so links already sent keep working
alter table saved_result add column shared boolean not null default true;
