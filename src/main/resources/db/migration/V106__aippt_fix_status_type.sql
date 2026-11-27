-- V106: 修正 aippt_templates.status 列类型。
--
-- V105 建表时 status 用了 TINYINT，但实体 AipptTemplateEntity.status 是 Java int，
-- Hibernate schema-validation 在启动期报
-- "wrong column type encountered in column [status]; found tinyint, expecting integer"。
-- 这里改成 INT 与实体一致；对尚未建过该表的环境，V105(TINYINT) → V106(INT) 连续执行
-- 结果同样是 INT，幂等无副作用。

ALTER TABLE aippt_templates MODIFY COLUMN status INT NOT NULL DEFAULT 1;
