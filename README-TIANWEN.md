# 天问AI - RuoYi SpringBoot3 + Vue2 集成版 V2

本包已经把 `server.py` 中的 H5 用户/推荐/签到/解锁/后台管理逻辑迁入用户提供的真实 RuoYi 工程，并把管理后台页面直接合并到用户提供的 RuoYi Vue2 工程。

## 目录

- `backend/`：基于 `RuoYi-Vue-springboot3` 的后端完整工程
- `admin-ui/`：基于 `RuoYi-Vue2-master` 的管理后台完整工程
- `backend/sql/tianwen_ai.sql`：天问AI业务表
- `backend/sql/tianwen_menu.sql`：RuoYi菜单和权限
- `docs/nginx-example.conf`：Nginx示例

## 已迁移接口

### UniApp/H5

- POST `/api/h5/auth/register`
- POST `/api/h5/auth/login`
- GET `/api/h5/me`
- GET `/api/h5/recommendations`
- GET `/api/h5/recommendations/{id}`
- POST `/api/h5/checkin`
- POST `/api/h5/unlock`

### 兼容旧上游接口

- GET `/api/summary`
- GET `/api/review`
- GET `/api/recommendations`
- POST `/api/settle-overdue`

### RuoYi管理后台

- GET `/tianwen/users`
- POST `/tianwen/users/adjust`
- GET `/tianwen/recommendations`
- POST `/tianwen/recommendations/update`
- POST `/tianwen/recommendations/reset`

后台登录/退出不再使用 Python `h5_admin_session`，直接使用 RuoYi 原生 `/login`、JWT、Redis 和权限体系。

## 数据库

先执行：

1. RuoYi 原始 SQL
2. `backend/sql/tianwen_ai.sql`
3. `backend/sql/tianwen_menu.sql`

业务表：

- `tw_h5_user`
- `tw_h5_session`
- `tw_h5_recommend_unlock`
- `tw_h5_account_log`
- `tw_h5_recommend_override`
- `tw_h5_checkin`

## 管理后台菜单

执行菜单 SQL 后：

- 天问AI
  - H5用户
  - 推荐管理

权限：

- `tianwen:user:list`
- `tianwen:user:adjust`
- `tianwen:recommend:list`
- `tianwen:recommend:edit`
- `tianwen:recommend:reset`

## 后端配置

已经写入 `backend/ruoyi-admin/src/main/resources/application.yml`：

```yaml
tianwen:
  ai:
    upstream-base: https://ai.lookfunny.xyz
    session-seconds: 2592000
    free-view-count: 3
    checkin-reward-days: 7
    checkin-reward-free-views: 1
    default-min-rate: 90
    default-model: jiebao
    paid-unlock-amount: '9.90'
    allow-manual-paid-unlock: false
    upstream-cache-seconds: 20
```

`upstream-base` 目前仍指向真正产生赛事推荐数据的 AI 数据服务。此次迁移的是 `server.py` 这一层；如果连抓取、赔率计算、模型计算、自动结算也要全部改 Java，还需要继续迁移真正产生 `/api/recommendations` 数据的 Python 引擎。

## 解锁逻辑

Java 解锁接口成功后会直接返回完整推荐：

```json
{
  "code": 200,
  "msg": "解锁成功",
  "user": {},
  "recommendation": {}
}
```

因此 UniApp 可以直接使用 `recommendation` 更新详情页，不再出现解锁成功但仍显示脱敏字段的问题。

## 管理后台前端

已加入：

- `admin-ui/src/api/tianwen/user.js`
- `admin-ui/src/api/tianwen/recommend.js`
- `admin-ui/src/views/tianwen/user/index.vue`
- `admin-ui/src/views/tianwen/recommend/index.vue`

路由由 RuoYi 动态菜单产生，无需手工修改 `router/index.js`。

## 说明

当前执行环境没有安装 Maven，所以未能在本地执行 `mvn package`；代码已按用户上传的 RuoYi SpringBoot3 / Java17 包结构直接集成。建议在开发机执行：

```bash
cd backend
mvn clean package -DskipTests
```

管理后台：

```bash
cd admin-ui
npm install
npm run dev
```
