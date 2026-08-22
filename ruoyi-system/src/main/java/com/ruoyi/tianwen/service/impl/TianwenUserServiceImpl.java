package com.ruoyi.tianwen.service.impl;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.tianwen.config.TianwenAiProperties;
import com.ruoyi.tianwen.domain.TianwenCheckin;
import com.ruoyi.tianwen.domain.TianwenH5Session;
import com.ruoyi.tianwen.domain.TianwenH5User;
import com.ruoyi.tianwen.domain.TianwenVipLevel;
import com.ruoyi.tianwen.mapper.TianwenCheckinMapper;
import com.ruoyi.tianwen.mapper.TianwenH5UserMapper;
import com.ruoyi.tianwen.mapper.TianwenSessionMapper;
import com.ruoyi.tianwen.mapper.TianwenUnlockMapper;
import com.ruoyi.tianwen.service.ITianwenUserService;
import com.ruoyi.tianwen.util.TianwenPasswordUtil;
import com.ruoyi.tianwen.util.TianwenTokenUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TianwenUserServiceImpl implements ITianwenUserService {

    private static final Pattern PHONE =
            Pattern.compile("^1[3-9]\\d{9}$|^\\+?[1-9]\\d{7,14}$");

    /**
     * 新注册用户统一使用 BCrypt。
     * BCrypt 自带随机 salt，因此 tw_h5_user.salt 对新用户保存空字符串即可。
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER =
            new BCryptPasswordEncoder();

    /**
     * 同一 token 在首页 -> 详情 -> 解锁的短时间内会被重复校验。
     * 30 秒缓存可以避免每个接口都查询 tw_h5_session + tw_h5_user。
     */
    private static final long USER_CACHE_MILLIS = 30_000L;

    private final Map<String, CachedUser> userCache =
            new ConcurrentHashMap<>();
    private final TianwenAiProperties properties;
    private final TianwenH5UserMapper userMapper;
    private final TianwenSessionMapper sessionMapper;
    private final TianwenUnlockMapper unlockMapper;
    private final TianwenCheckinMapper checkinMapper;
    public TianwenUserServiceImpl(
            TianwenAiProperties properties,
            TianwenH5UserMapper userMapper,
            TianwenSessionMapper sessionMapper,
            TianwenUnlockMapper unlockMapper,
            TianwenCheckinMapper checkinMapper) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.unlockMapper = unlockMapper;
        this.checkinMapper = checkinMapper;
    }

    @Override
    @Transactional
    public Map<String, Object> register(String phone, String password) {
        phone = normalizePhone(phone);
        password = password == null ? "" : password;

        if (!PHONE.matcher(phone).matches()) {
            throw new ServiceException("手机号格式不正确");
        }
        if (password.length() < 6) {
            throw new ServiceException("密码至少6位");
        }
        if (userMapper.selectByPhone(phone) != null) {
            throw new ServiceException("手机号已注册，请直接登录");
        }

        TianwenH5User user = new TianwenH5User();
        user.setPhone(phone);

        // 新注册用户直接使用 BCrypt。
        user.setPasswordHash(PASSWORD_ENCODER.encode(password));

        // BCrypt 的 salt 已包含在 password_hash 内。
        // 如果数据库 salt 是 NOT NULL，这里保存空字符串。
        user.setSalt("");

        user.setBalance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        user.setFreeViewsRemaining(properties.getFreeViewCount());

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("手机号已注册，请直接登录");
        }

        // useGeneratedKeys 已回填主键，不再额外 SELECT。
        return authResult("注册成功", user);
    }

    @Override
    @Transactional
    public Map<String, Object> login(String phone, String password) {
        phone = normalizePhone(phone);
        password = password == null ? "" : password;

        TianwenH5User user = userMapper.selectByPhone(phone);
        if (user == null) {
            throw new ServiceException("手机号或密码错误");
        }

        String storedHash = trimToEmpty(user.getPasswordHash());
        String salt = trimToEmpty(user.getSalt());

        if (storedHash.isEmpty()) {
            throw new ServiceException("手机号或密码错误");
        }

        boolean matched = false;
        boolean legacyPassword = false;

        /*
         * 新 Java 用户：
         * BCrypt hash 一般以 $2a$ / $2b$ / $2y$ 开头。
         */
        if (isBcryptHash(storedHash)) {
            try {
                matched = PASSWORD_ENCODER.matches(password, storedHash);
            } catch (Exception e) {
                matched = false;
            }
        } else {
            /*
             * 兼容之前 Python / 旧 Java PBKDF2 用户。
             * 这里继续使用项目现有 TianwenPasswordUtil。
             */
            legacyPassword = true;
            if (!salt.isEmpty()) {
                try {
                    matched = TianwenPasswordUtil.matches(
                            password,
                            storedHash,
                            salt
                    );
                } catch (Exception e) {
                    matched = false;
                }
            }
        }

        if (!matched) {
            throw new ServiceException("手机号或密码错误");
        }

        /*
         * 老 PBKDF2 用户登录成功后自动升级为 BCrypt。
         * 用户不需要重置密码。
         */
        if (legacyPassword) {
            String newHash = PASSWORD_ENCODER.encode(password);
            userMapper.updatePassword(user.getId(), newHash, "");
            user.setPasswordHash(newHash);
            user.setSalt("");
        }

        return authResult("登录成功", user);
    }

    private Map<String, Object> authResult(String msg, TianwenH5User user) {
        String token = TianwenTokenUtil.createToken();

        TianwenH5Session session = new TianwenH5Session();
        session.setToken(token);
        session.setUserId(user.getId());
        session.setExpiresAt(
                System.currentTimeMillis() / 1000
                        + properties.getSessionSeconds()
        );
        sessionMapper.insert(session);

        cacheUser(token, user);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("msg", msg);
        out.put("token", token);
        out.put("user", publicUser(user));

        // 登录/注册首屏不需要签到明细，避免多查签到表。
        out.put("checkin", null);
        return out;
    }

    @Override
    public TianwenH5User currentUser(String bearerToken) {
        String token = cleanBearer(bearerToken);
        if (token.isEmpty()) {
            return null;
        }

        long now = System.currentTimeMillis();
        CachedUser cached = userCache.get(token);

        if (cached != null) {
            if (cached.expireAt > now) {
                return cached.user;
            }
            userCache.remove(token);
        }

        TianwenH5User user =
                sessionMapper.selectUserByToken(
                        token,
                        now / 1000
                );

        if (user != null) {
            cacheUser(token, user);
        }

        return user;
    }

    @Override
    public Map<String, Object> me(String bearerToken) {
        TianwenH5User user = currentUser(bearerToken);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("user", user == null ? null : publicUser(user));
        out.put(
                "checkin",
                user == null ? null : checkinStatus(user.getId())
        );
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> checkin(String bearerToken) {
        String token = cleanBearer(bearerToken);
        TianwenH5User user = requireUser(token);
        LocalDate today = LocalDate.now();

        TianwenCheckin existing =
                checkinMapper.selectByDate(user.getId(), today);

        if (existing != null) {
            return checkinResult("今日已签到", false, user);
        }

        TianwenCheckin latest =
                checkinMapper.selectLatestBefore(user.getId(), today);

        int streak =
                latest != null
                        && today.minusDays(1).equals(latest.getCheckinDate())
                        ? latest.getStreak() + 1
                        : 1;

        int rewardDays =
                Math.max(1, properties.getCheckinRewardDays());

        boolean reward = streak % rewardDays == 0;

        TianwenCheckin c = new TianwenCheckin();
        c.setUserId(user.getId());
        c.setCheckinDate(today);
        c.setStreak(streak);
        c.setRewardGranted(reward ? 1 : 0);

        try {
            checkinMapper.insert(c);
        } catch (DuplicateKeyException e) {
            return checkinResult("今日已签到", false, user);
        }

        if (reward) {
            int delta = properties.getCheckinRewardFreeViews();
            userMapper.addFreeViews(user.getId(), delta);

            Integer current = user.getFreeViewsRemaining();
            user.setFreeViewsRemaining(
                    (current == null ? 0 : current) + delta
            );
            cacheUser(token, user);
        }

        String msg =
                reward
                        ? "签到成功，已赠送 "
                        + properties.getCheckinRewardFreeViews()
                        + " 次免费查看"
                        : "签到成功";

        return checkinResult(msg, reward, user);
    }

    private Map<String, Object> checkinResult(
            String msg,
            boolean reward,
            TianwenH5User user) {

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("msg", msg);
        out.put("rewardGranted", reward);
        out.put("user", publicUser(user));
        out.put("checkin", checkinStatus(user.getId()));
        return out;
    }

    /**
     * V15 极速解锁。
     *
     * 解锁接口只负责：
     * 1. 校验 token（优先 JVM 缓存）
     * 2. INSERT IGNORE 解锁记录
     * 3. 免费解锁时原子扣减免费次数
     *
     * 不查推荐详情、不重新查用户、不先 SELECT 是否已解锁。
     */
    @Override
    @Transactional
    public Map<String, Object> unlock(
            String bearerToken,
            String recommendationId,
            String unlockType) {

        final long t0 = System.nanoTime();

        String token = cleanBearer(bearerToken);
        TianwenH5User user = requireUser(token);

        final long t1 = System.nanoTime();

        String recId =
                recommendationId == null
                        ? ""
                        : recommendationId.trim();

        if (recId.isEmpty()) {
            throw new ServiceException("recommendationId不能为空");
        }

        try {
            Long.parseLong(recId);
        } catch (Exception e) {
            throw new ServiceException("recommendationId格式错误");
        }

        String type =
                unlockType == null
                        || unlockType.trim().isEmpty()
                        ? "free"
                        : unlockType.trim();

        BigDecimal amount;

        if ("free".equals(type)) {
            amount = BigDecimal.ZERO;
        } else if ("paid".equals(type)) {
            if (!properties.isAllowManualPaidUnlock()) {
                throw new ServiceException(
                        "请先接入支付回调，确认支付后再创建付费解锁记录"
                );
            }
            amount = new BigDecimal(
                    properties.getPaidUnlockAmount()
            );
        } else {
            throw new ServiceException("unlockType错误");
        }

        /*
         * 唯一索引 + INSERT IGNORE：
         * 1 = 本次新解锁
         * 0 = 之前已经解锁
         *
         * 比“selectOne -> insert”少一次数据库往返。
         */
        int inserted = unlockMapper.insertIgnore(
                user.getId(),
                recId,
                type,
                amount
        );

        final long t2 = System.nanoTime();

        if (inserted == 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 200);
            out.put("msg", "已解锁");
            out.put("unlocked", true);
            out.put("recommendationId", recId);
            out.put("user", publicUser(user));
            out.put(
                    "debugCostMs",
                    unlockCost(t0, t1, t2, t2)
            );
            return out;
        }

        if ("free".equals(type)) {
            /*
             * 先 INSERT，再扣次数。
             * 如果次数不足抛异常，Spring 事务会同时回滚 INSERT。
             */
            int affected =
                    userMapper.decrementFreeView(user.getId());

            if (affected <= 0) {
                throw new ServiceException(
                        "免费次数已用完，请付费解锁"
                );
            }

            Integer remain = user.getFreeViewsRemaining();
            user.setFreeViewsRemaining(
                    Math.max(
                            0,
                            remain == null ? 0 : remain - 1
                    )
            );
        }

        final long t3 = System.nanoTime();

        cacheUser(token, user);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 200);
        out.put("msg", "解锁成功");
        out.put("unlocked", true);
        out.put("recommendationId", recId);
        out.put("user", publicUser(user));

        // 暂时保留，用于 Network Preview 精确判断后端耗时。
        out.put(
                "debugCostMs",
                unlockCost(t0, t1, t2, t3)
        );

        return out;
    }

    @Override
    public Map<String, Object> adminUsers(
            String q,
            int page,
            int pageSize) {

        page = Math.max(1, page);
        pageSize = Math.min(100, Math.max(1, pageSize));

        int offset = (page - 1) * pageSize;

        List<Map<String, Object>> items =
                userMapper.selectAdminList(
                        q,
                        offset,
                        pageSize
                );

        long total =
                userMapper.countAdminList(q);

        Map<String, Object> pagination =
                new LinkedHashMap<>();

        pagination.put("page", page);
        pagination.put("pageSize", pageSize);
        pagination.put("total", total);
        pagination.put(
                "hasMore",
                offset + items.size() < total
        );

        Map<String, Object> out =
                new LinkedHashMap<>();

        out.put("code", 200);
        out.put("items", items);
        out.put("pagination", pagination);

        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> adminAdjust(
            Long userId,
            BigDecimal balanceDelta,
            Integer freeViewsDelta,
            String remark,
            String adminUsername) {

        TianwenH5User user =
                userMapper.selectById(userId);

        if (user == null) {
            throw new ServiceException("用户不存在");
        }

        BigDecimal bd =
                balanceDelta == null
                        ? BigDecimal.ZERO
                        : balanceDelta;

        int fd =
                freeViewsDelta == null
                        ? 0
                        : freeViewsDelta;

        if (bd.compareTo(BigDecimal.ZERO) == 0 && fd == 0) {
            throw new ServiceException(
                    "请输入加钱金额或免费次数"
            );
        }

        BigDecimal currentBalance =
                user.getBalance() == null
                        ? BigDecimal.ZERO
                        : user.getBalance();

        int currentFree =
                user.getFreeViewsRemaining() == null
                        ? 0
                        : user.getFreeViewsRemaining();

        BigDecimal balanceAfter =
                currentBalance
                        .add(bd)
                        .max(BigDecimal.ZERO)
                        .setScale(
                                2,
                                RoundingMode.HALF_UP
                        );

        int freeAfter =
                Math.max(0, currentFree + fd);

        userMapper.updateAsset(
                userId,
                balanceAfter,
                freeAfter
        );

        userMapper.insertAccountLog(
                userId,
                bd,
                fd,
                balanceAfter,
                freeAfter,
                remark,
                adminUsername
        );

        Map<String, Object> out =
                new LinkedHashMap<>();

        out.put("code", 200);
        out.put("msg", "调整成功");
        user.setBalance(balanceAfter);
        user.setFreeViewsRemaining(freeAfter);
        out.put("user", publicUser(user));

        return out;
    }

    private TianwenH5User requireUser(String bearerToken) {
        TianwenH5User user =
                currentUser(bearerToken);

        if (user == null) {
            throw new ServiceException(
                    "请先登录或登录已过期"
            );
        }

        return user;
    }

    private String cleanBearer(String value) {
        if (value == null) {
            return "";
        }

        String v = value.trim();

        if (v.regionMatches(
                true,
                0,
                "Bearer ",
                0,
                7)) {
            return v.substring(7).trim();
        }

        return v;
    }

    private Map<String, Object> publicUser(TianwenH5User user) {
        Map<String, Object> m =
                new LinkedHashMap<>();

        m.put("id", user.getId());
        m.put("phone", user.getPhone());
        m.put("balance", user.getBalance());
        m.put(
                "freeViewsRemaining",
                user.getFreeViewsRemaining()
        );

        TianwenVipLevel level =
                TianwenVipLevel.of(
                        user.getVipLevel()
                );

        boolean vipActive =
                level != TianwenVipLevel.NORMAL
                        && (
                        user.getVipExpireAt() == null
                                || user.getVipExpireAt()
                                .isAfter(
                                        java.time.LocalDateTime.now()
                                )
                );

        TianwenVipLevel effective =
                vipActive
                        ? level
                        : TianwenVipLevel.NORMAL;

        m.put(
                "vipLevel",
                effective.getLevel()
        );
        m.put(
                "vipName",
                effective.getLabel()
        );
        m.put("vipActive", vipActive);
        m.put(
                "vipStartAt",
                user.getVipStartAt()
        );
        m.put(
                "vipExpireAt",
                user.getVipExpireAt()
        );
        m.put(
                "vipMaxFreeRate",
                effective.getMaxFreeRate()
        );
        m.put(
                "vipAllAccess",
                effective.isAllAccess()
        );

        return m;
    }

    private Map<String, Object> checkinStatus(Long userId) {
        LocalDate today = LocalDate.now();

        TianwenCheckin todayRow =
                checkinMapper.selectByDate(
                        userId,
                        today
                );

        TianwenCheckin latest =
                checkinMapper.selectLatest(userId);

        boolean checked =
                todayRow != null;

        boolean rewardToday =
                checked
                        && Integer.valueOf(1)
                        .equals(
                                todayRow.getRewardGranted()
                        );

        int streak;
        LocalDate lastDate;

        if (todayRow != null) {
            streak = todayRow.getStreak();
            lastDate = todayRow.getCheckinDate();
        } else if (
                latest != null
                        && today.minusDays(1)
                        .equals(
                                latest.getCheckinDate()
                        )) {
            streak = latest.getStreak();
            lastDate = latest.getCheckinDate();
        } else {
            streak = 0;
            lastDate =
                    latest == null
                            ? null
                            : latest.getCheckinDate();
        }

        int days =
                Math.max(
                        1,
                        properties.getCheckinRewardDays()
                );

        int cycle =
                streak == 0
                        ? 0
                        : streak % days;

        int progress =
                rewardToday && cycle == 0
                        ? days
                        : cycle;

        int next =
                rewardToday
                        ? 0
                        : (
                        cycle == 0
                                ? days
                                : days - cycle
                );

        Map<String, Object> m =
                new LinkedHashMap<>();

        m.put("checkedInToday", checked);
        m.put("streak", streak);
        m.put("progress", progress);
        m.put("rewardDays", days);
        m.put(
                "rewardFreeViews",
                properties.getCheckinRewardFreeViews()
        );
        m.put("nextRewardIn", next);
        m.put(
                "rewardGrantedToday",
                rewardToday
        );
        m.put(
                "lastCheckinDate",
                lastDate
        );

        return m;
    }

    private void cacheUser(String token, TianwenH5User user) {
        if (token == null || token.isEmpty() || user == null) {
            return;
        }

        userCache.put(
                token,
                new CachedUser(
                        user,
                        System.currentTimeMillis() + USER_CACHE_MILLIS
                )
        );
    }

    private Map<String, Long> unlockCost(
            long t0,
            long t1,
            long t2,
            long t3) {

        Map<String, Long> m = new LinkedHashMap<>();
        m.put("currentUser", elapsedMs(t0, t1));
        m.put("insertIgnore", elapsedMs(t1, t2));
        m.put("decrement", elapsedMs(t2, t3));
        m.put("total", elapsedMs(t0, t3));
        return m;
    }

    private long elapsedMs(long start, long end) {
        return (end - start) / 1_000_000L;
    }

    private String normalizePhone(String phone) {
        return phone == null
                ? ""
                : phone.trim();
    }

    private String trimToEmpty(String value) {
        return value == null
                ? ""
                : value.trim();
    }

    private boolean isBcryptHash(String hash) {
        return hash.startsWith("$2a$")
                || hash.startsWith("$2b$")
                || hash.startsWith("$2y$");
    }

    private static final class CachedUser {
        private final TianwenH5User user;
        private final long expireAt;

        private CachedUser(TianwenH5User user, long expireAt) {
            this.user = user;
            this.expireAt = expireAt;
        }
    }
}
