create table customers (
    id    bigint primary key,
    name  varchar(100) not null,
    email varchar(150) not null
);

create table orders (
    id          bigint primary key,
    customer_id bigint         not null references customers (id),
    placed_at   date           not null,
    total       numeric(10, 2) not null
);

create table audit_events (
    id          bigint primary key,
    occurred_at timestamp    not null,
    type        varchar(30)  not null,
    actor_email varchar(150) not null,
    message     varchar(255) not null
);
