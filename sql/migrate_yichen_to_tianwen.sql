-- 把 yichen 里天问后端用到的数据拷进已有的 tianwen 库。
--
-- 拷贝：
--   tw_h5_user / session / checkin / recommend_unlock / recommend_override / account_log
--   tw_h5_vip_plan
--   football_ai_recommend_record
--   t_leisu_schedule
--
-- 不拷贝：若依 sys_*、漫画、Flowable、赔率明细、备份表。
-- tianwen 里的后台菜单和账号保持不动。
-- yichen 只读，不删除。
--
-- 两个库在同一台 MySQL 上。建议在低峰执行，推荐表和赛程表大约 200MB。
--
--   mysql -h <host> -u <user> -p < sql/migrate_yichen_to_tianwen.sql
--
-- 跑完后把 application-druid.yml 的库名从 yichen 改成 tianwen。
-- 脚本可以重复执行：已有主键或唯一键的行会跳过；赛程表只在 tianwen 侧为空时导入。

SET NAMES utf8mb4;
SET SESSION foreign_key_checks = 0;

-- ---------------------------------------------------------------------------
-- 1. tianwen.tw_h5_user 补会员字段。yichen 有，当前 tianwen 没有。
-- ---------------------------------------------------------------------------

SET @has_vip_level := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'tianwen'
      AND TABLE_NAME = 'tw_h5_user'
      AND COLUMN_NAME = 'vip_level'
);

SET @ddl := IF(
    @has_vip_level = 0,
    'ALTER TABLE tianwen.tw_h5_user
        ADD COLUMN vip_level tinyint NOT NULL DEFAULT 0 COMMENT ''0普通 1VIP 2SVIP 3至尊VIP'' AFTER free_views_remaining,
        ADD COLUMN vip_start_at datetime NULL COMMENT ''会员开始时间'' AFTER vip_level,
        ADD COLUMN vip_expire_at datetime NULL COMMENT ''会员到期时间；NULL在level>0时视为永久会员'' AFTER vip_start_at',
    'SELECT ''tw_h5_user.vip_level already exists'' AS migrate_note'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_vip_idx := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'tianwen'
      AND TABLE_NAME = 'tw_h5_user'
      AND INDEX_NAME = 'idx_tw_user_vip_expire'
);

SET @ddl := IF(
    @has_vip_idx = 0,
    'ALTER TABLE tianwen.tw_h5_user ADD KEY idx_tw_user_vip_expire (vip_level, vip_expire_at)',
    'SELECT ''idx_tw_user_vip_expire already exists'' AS migrate_note'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 2. H5 业务数据。显式带上 id，会话和解锁记录才能对上原用户。
-- ---------------------------------------------------------------------------

INSERT IGNORE INTO tianwen.tw_h5_user (
    id, phone, password_hash, salt, balance, free_views_remaining,
    vip_level, vip_start_at, vip_expire_at, created_at, updated_at
)
SELECT
    id, phone, password_hash, salt, balance, free_views_remaining,
    vip_level, vip_start_at, vip_expire_at, created_at, updated_at
FROM yichen.tw_h5_user;

INSERT IGNORE INTO tianwen.tw_h5_session (token, user_id, expires_at, created_at)
SELECT token, user_id, expires_at, created_at
FROM yichen.tw_h5_session;

INSERT IGNORE INTO tianwen.tw_h5_checkin (
    id, user_id, checkin_date, streak, reward_granted, created_at
)
SELECT id, user_id, checkin_date, streak, reward_granted, created_at
FROM yichen.tw_h5_checkin;

INSERT IGNORE INTO tianwen.tw_h5_recommend_unlock (
    id, user_id, recommendation_id, unlock_type, amount, created_at
)
SELECT id, user_id, recommendation_id, unlock_type, amount, created_at
FROM yichen.tw_h5_recommend_unlock;

INSERT IGNORE INTO tianwen.tw_h5_recommend_override (
    recommendation_id, override_json, admin_username, created_at, updated_at
)
SELECT recommendation_id, override_json, admin_username, created_at, updated_at
FROM yichen.tw_h5_recommend_override;

INSERT IGNORE INTO tianwen.tw_h5_account_log (
    id, user_id, balance_delta, free_views_delta, balance_after, free_views_after,
    remark, admin_username, created_at
)
SELECT
    id, user_id, balance_delta, free_views_delta, balance_after, free_views_after,
    remark, admin_username, created_at
FROM yichen.tw_h5_account_log;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.tw_h5_user);
SET @ddl := CONCAT('ALTER TABLE tianwen.tw_h5_user AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.tw_h5_checkin);
SET @ddl := CONCAT('ALTER TABLE tianwen.tw_h5_checkin AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.tw_h5_recommend_unlock);
SET @ddl := CONCAT('ALTER TABLE tianwen.tw_h5_recommend_unlock AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.tw_h5_account_log);
SET @ddl := CONCAT('ALTER TABLE tianwen.tw_h5_account_log AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. tianwen 里没有的三张表：按 yichen 的结构建表，再灌数据。
--    推荐表靠主键和唯一键跳过已存在行。
--    赛程表没有主键，只在目标表为空时导入，避免重复执行翻倍。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tianwen.tw_h5_vip_plan LIKE yichen.tw_h5_vip_plan;

INSERT IGNORE INTO tianwen.tw_h5_vip_plan
SELECT * FROM yichen.tw_h5_vip_plan;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.tw_h5_vip_plan);
SET @ddl := CONCAT('ALTER TABLE tianwen.tw_h5_vip_plan AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS tianwen.football_ai_recommend_record LIKE yichen.football_ai_recommend_record;

INSERT IGNORE INTO tianwen.football_ai_recommend_record
SELECT * FROM yichen.football_ai_recommend_record;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.football_ai_recommend_record);
SET @ddl := CONCAT('ALTER TABLE tianwen.football_ai_recommend_record AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS tianwen.t_leisu_schedule LIKE yichen.t_leisu_schedule;

SET @schedule_rows := (SELECT COUNT(*) FROM tianwen.t_leisu_schedule);
SET @ddl := IF(
    @schedule_rows = 0,
    'INSERT INTO tianwen.t_leisu_schedule SELECT * FROM yichen.t_leisu_schedule',
    'SELECT ''t_leisu_schedule already loaded, skipped'' AS migrate_note'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @next_id := (SELECT IFNULL(MAX(id), 0) + 1 FROM tianwen.t_leisu_schedule);
SET @ddl := CONCAT('ALTER TABLE tianwen.t_leisu_schedule AUTO_INCREMENT = ', @next_id);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET SESSION foreign_key_checks = 1;

-- ---------------------------------------------------------------------------
-- 4. 对账。左右行数一致即可切库。
-- ---------------------------------------------------------------------------

SELECT 'tw_h5_user' AS table_name,
       (SELECT COUNT(*) FROM yichen.tw_h5_user) AS yichen_rows,
       (SELECT COUNT(*) FROM tianwen.tw_h5_user) AS tianwen_rows
UNION ALL
SELECT 'tw_h5_session',
       (SELECT COUNT(*) FROM yichen.tw_h5_session),
       (SELECT COUNT(*) FROM tianwen.tw_h5_session)
UNION ALL
SELECT 'tw_h5_checkin',
       (SELECT COUNT(*) FROM yichen.tw_h5_checkin),
       (SELECT COUNT(*) FROM tianwen.tw_h5_checkin)
UNION ALL
SELECT 'tw_h5_recommend_unlock',
       (SELECT COUNT(*) FROM yichen.tw_h5_recommend_unlock),
       (SELECT COUNT(*) FROM tianwen.tw_h5_recommend_unlock)
UNION ALL
SELECT 'tw_h5_recommend_override',
       (SELECT COUNT(*) FROM yichen.tw_h5_recommend_override),
       (SELECT COUNT(*) FROM tianwen.tw_h5_recommend_override)
UNION ALL
SELECT 'tw_h5_account_log',
       (SELECT COUNT(*) FROM yichen.tw_h5_account_log),
       (SELECT COUNT(*) FROM tianwen.tw_h5_account_log)
UNION ALL
SELECT 'tw_h5_vip_plan',
       (SELECT COUNT(*) FROM yichen.tw_h5_vip_plan),
       (SELECT COUNT(*) FROM tianwen.tw_h5_vip_plan)
UNION ALL
SELECT 'football_ai_recommend_record',
       (SELECT COUNT(*) FROM yichen.football_ai_recommend_record),
       (SELECT COUNT(*) FROM tianwen.football_ai_recommend_record)
UNION ALL
SELECT 't_leisu_schedule',
       (SELECT COUNT(*) FROM yichen.t_leisu_schedule),
       (SELECT COUNT(*) FROM tianwen.t_leisu_schedule);
