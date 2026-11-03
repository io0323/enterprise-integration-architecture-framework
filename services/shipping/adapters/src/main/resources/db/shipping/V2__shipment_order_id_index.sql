-- 出荷の照会は注文の ID で読む(INT-SHIPPING-003。ADR-0042 §4)。
CREATE INDEX shipment_order_id ON shipment (order_id);
