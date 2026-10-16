-- One database and one account per service; each account can only reach its own database.
-- Development passwords only. Tables are created by each service's Flyway migrations.

CREATE DATABASE shop_order;
CREATE DATABASE shop_inventory;
CREATE DATABASE shop_payment;

CREATE USER 'order_svc'@'%' IDENTIFIED BY 'order-dev';
CREATE USER 'inventory_svc'@'%' IDENTIFIED BY 'inventory-dev';
CREATE USER 'payment_svc'@'%' IDENTIFIED BY 'payment-dev';

GRANT ALL PRIVILEGES ON shop_order.* TO 'order_svc'@'%';
GRANT ALL PRIVILEGES ON shop_inventory.* TO 'inventory_svc'@'%';
GRANT ALL PRIVILEGES ON shop_payment.* TO 'payment_svc'@'%';
