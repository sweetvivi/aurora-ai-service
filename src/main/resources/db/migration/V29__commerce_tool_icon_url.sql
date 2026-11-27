-- V29: 电商工具支持「自定义图标图片」
--
-- 背景：glyph 只有两种形态（remixicon 类名 / 单个汉字），运营想用自家设计的
-- 小图标（比如美妆工具用一支口红）就必须改代码。
--
-- 做法：加一列 icon_url，渲染优先级 image > glyph。为空时前台仍按 glyph 渲染，
-- 所以这一列是纯增量，历史数据不用回填。
--
-- 为什么不复用 glyph：glyph 是「字/类名」，塞 URL 进去要靠前缀猜类型，
-- 清理与校验都会变模糊；分成两列后，管理端可以直接提供「图片 / 文字」二选一。

ALTER TABLE commerce_tools ADD COLUMN icon_url VARCHAR(1024) NULL AFTER glyph;
