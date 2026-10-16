-- Same SKUs as the order service's product catalogue. SKU-2001 is a limited item that sells out under load.
INSERT INTO stock (sku, total, available, updated_at) VALUES
    ('SKU-1001', 100000, 100000, NOW(3)),
    ('SKU-1002', 100000, 100000, NOW(3)),
    ('SKU-1003', 100000, 100000, NOW(3)),
    ('SKU-1004', 100000, 100000, NOW(3)),
    ('SKU-1005', 100000, 100000, NOW(3)),
    ('SKU-1006', 100000, 100000, NOW(3)),
    ('SKU-1007', 100000, 100000, NOW(3)),
    ('SKU-1008', 100000, 100000, NOW(3)),
    ('SKU-2001', 20, 20, NOW(3));
