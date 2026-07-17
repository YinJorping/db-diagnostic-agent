-- ============================================================
-- Docker PostgreSQL 初始化：构造诊断场景
-- 目标：制造真实数据库性能问题，供 Agent 诊断
-- ============================================================

-- 启用 pg_stat_statements（已在 docker-compose 中配置 shared_preload_libraries）
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ============================================================
-- 场景 1: 大表缺索引 — 全表扫描 (HIGH)
-- ============================================================
DROP TABLE IF EXISTS orders_large CASCADE;
CREATE TABLE orders_large (
    id          SERIAL,
    customer_id INTEGER      NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    amount      NUMERIC(12,2),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- 插入 50 万行，status='pending' 约占 30%，无索引 → 全表扫描
INSERT INTO orders_large (customer_id, status, amount, created_at)
SELECT
    (random() * 10000)::INT,
    CASE WHEN random() < 0.3 THEN 'pending'
         WHEN random() < 0.6 THEN 'shipped'
         WHEN random() < 0.9 THEN 'delivered'
         ELSE 'cancelled' END,
    (random() * 10000)::NUMERIC(12,2),
    NOW() - (random() * 365 || ' days')::INTERVAL
FROM generate_series(1, 500000);
ANALYZE orders_large;

-- ============================================================
-- 场景 2: 排序字段缺索引 — filesort (MEDIUM)
-- ============================================================
DROP TABLE IF EXISTS event_logs CASCADE;
CREATE TABLE event_logs (
    id          SERIAL,
    event_type  VARCHAR(32)  NOT NULL,
    message     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
-- 插入 30 万行，created_at 无索引 → ORDER BY 触发 filesort
INSERT INTO event_logs (event_type, message, created_at)
SELECT
    CASE WHEN random() < 0.5 THEN 'INFO'
         WHEN random() < 0.3 THEN 'WARN'
         ELSE 'ERROR' END,
    'Log entry ' || i,
    NOW() - (random() * 90 || ' days')::INTERVAL
FROM generate_series(1, 300000) AS i;
ANALYZE event_logs;

-- ============================================================
-- 场景 3: 多表 JOIN 缺索引 — 慢查询 (HIGH)
-- ============================================================
DROP TABLE IF EXISTS products CASCADE;
CREATE TABLE products (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    category_id INTEGER      NOT NULL,
    price       NUMERIC(10,2)
);
-- 1000 个产品
INSERT INTO products (name, category_id, price)
SELECT 'Product-' || i, (random() * 50)::INT + 1, (random() * 500)::NUMERIC(10,2)
FROM generate_series(1, 1000) AS i;

DROP TABLE IF EXISTS order_items CASCADE;
CREATE TABLE order_items (
    id          SERIAL,
    order_id    INTEGER      NOT NULL,
    product_id  INTEGER      NOT NULL,
    quantity    INTEGER      NOT NULL DEFAULT 1
);
-- 200 万行订单明细，order_id 和 product_id 均无索引
INSERT INTO order_items (order_id, product_id, quantity)
SELECT
    (random() * 500000)::INT + 1,
    (random() * 1000)::INT + 1,
    (random() * 10)::INT + 1
FROM generate_series(1, 2000000) AS i;
ANALYZE order_items;
ANALYZE products;

-- ============================================================
-- 场景 4: 有索引的表（作为对比基准）
-- ============================================================
DROP TABLE IF EXISTS orders_indexed CASCADE;
CREATE TABLE orders_indexed (
    id          SERIAL PRIMARY KEY,
    status      VARCHAR(20)  NOT NULL,
    amount      NUMERIC(12,2),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_orders_status ON orders_indexed(status);
CREATE INDEX idx_orders_created ON orders_indexed(created_at);
-- 50 万行，但有索引
INSERT INTO orders_indexed (status, amount, created_at)
SELECT
    CASE WHEN random() < 0.3 THEN 'pending' ELSE 'done' END,
    (random() * 10000)::NUMERIC(12,2),
    NOW() - (random() * 365 || ' days')::INTERVAL
FROM generate_series(1, 500000);
ANALYZE orders_indexed;

-- ============================================================
-- 预热 pg_stat_statements：执行一些查询让它有数据可查
-- ============================================================
-- 确保 pg_stat_statements 是干净的
SELECT pg_stat_statements_reset();

-- 执行几次慢查询（全表扫描）
SELECT count(*) FROM orders_large WHERE status = 'pending';
SELECT * FROM orders_large WHERE status = 'shipped' LIMIT 1;
-- 执行一次排序查询（filesort）
SELECT * FROM event_logs ORDER BY created_at DESC LIMIT 100;
-- 执行一次三表 JOIN
SELECT o.id, p.name, oi.quantity
FROM orders_large o
JOIN order_items oi ON o.id = oi.order_id
JOIN products p ON oi.product_id = p.id
WHERE o.status = 'pending'
LIMIT 10;

-- ============================================================
-- 验证：检查各表行数
-- ============================================================
SELECT 'orders_large' AS table_name, count(*) AS row_count FROM orders_large
UNION ALL
SELECT 'event_logs', count(*) FROM event_logs
UNION ALL
SELECT 'order_items', count(*) FROM order_items
UNION ALL
SELECT 'products', count(*) FROM products
UNION ALL
SELECT 'orders_indexed', count(*) FROM orders_indexed;
