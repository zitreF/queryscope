insert into customers (id, name, email)
select x, 'Customer ' || x, 'customer' || x || '@example.com'
from system_range(1, 40);

insert into orders (id, customer_id, placed_at, total)
select x, mod(x, 37) + 1, dateadd(day, -mod(x * 7, 365), date '2026-10-01'), mod(x * 37, 500) + 19.99
from system_range(1, 240);

insert into audit_events (id, occurred_at, type, message)
select x,
       dateadd(second, -x, timestamp '2026-10-01 00:00:00'),
       case mod(x, 3) when 0 then 'PAYMENT' when 1 then 'SHIPMENT' else 'RETURN' end,
       'Order ' || x || case when mod(x, 7) = 0 then ' refund requested by customer' else ' processed' end
from system_range(1, 300000);
