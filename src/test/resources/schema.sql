-- H2 测试库的业务表由 Hibernate 按实体建（ddl-auto=create-drop），
-- 但 shedlock 不是实体，也不该为了测试硬塞一个实体进去，所以在这里单独建。
-- IF NOT EXISTS 让每个 Spring 上下文重复执行都不报错。
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP(3),
    locked_at TIMESTAMP(3),
    locked_by VARCHAR(255),
    PRIMARY KEY (name)
);
