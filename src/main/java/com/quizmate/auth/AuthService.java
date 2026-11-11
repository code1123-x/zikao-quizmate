package com.quizmate.auth;

import com.quizmate.common.CurrentUser;
import com.quizmate.common.QuizmatePaths;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}_-]{2,32}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final UserDatabaseService userDatabases;

    public AuthService(@Qualifier("authJdbc") JdbcTemplate jdbc, UserDatabaseService userDatabases) {
        this.jdbc = jdbc;
        this.userDatabases = userDatabases;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
                create table if not exists app_user (
                  id            integer primary key autoincrement,
                  username      text not null,
                  password_hash text not null,
                  salt          text not null,
                  is_admin      integer not null default 0,
                  raw_dir       text not null,
                  created_at    text not null
                )
                """);
        jdbc.execute("create unique index if not exists uk_app_user_name on app_user(lower(username))");
        jdbc.execute("""
                create table if not exists app_token (
                  token_hash   text primary key,
                  user_id      integer not null,
                  created_at   text not null,
                  expires_at   text not null,
                  last_used_at text
                )
                """);
        jdbc.execute("create index if not exists idx_app_token_user on app_token(user_id)");
        jdbc.update("delete from app_token where expires_at <= ?", LocalDateTime.now().toString());
        migrateExistingRawDirs();
    }

    /** 兼容早期账号：把 raw 根目录或 raw-users/xxx 迁到统一的 raw/<用户名>/。 */
    private void migrateExistingRawDirs() {
        List<RawDirRow> users = jdbc.query("""
                select id, username, raw_dir from app_user order by id
                """, (rs, i) -> new RawDirRow(rs.getLong("id"), rs.getString("username"),
                rs.getString("raw_dir")));
        java.util.Set<String> accountDirs = new java.util.HashSet<>();
        for (RawDirRow user : users) {
            accountDirs.add(user.username());
        }
        for (RawDirRow user : users) {
            String expected = "raw/" + user.username();
            if (expected.equals(user.rawDir())) {
                continue;
            }
            try {
                Path source = user.rawDir() == null || user.rawDir().isBlank()
                        ? Path.of("raw") : Path.of(user.rawDir()).toAbsolutePath().normalize();
                Path target = Path.of(expected).toAbsolutePath().normalize();
                Files.createDirectories(target);
                if (Files.isDirectory(source) && !source.equals(target)) {
                    try (Stream<Path> stream = Files.list(source)) {
                        for (Path child : stream.toList()) {
                            String name = child.getFileName().toString();
                            // 迁移旧 raw/ 时不要吞掉其他账号的目录
                            if (source.equals(Path.of("raw").toAbsolutePath().normalize())
                                    && accountDirs.contains(name)) {
                                continue;
                            }
                            Path dest = target.resolve(name);
                            if (!Files.exists(dest)) {
                                Files.move(child, dest);
                            }
                        }
                    }
                }
                jdbc.update("update app_user set raw_dir=? where id=?", expected, user.id());
                log.info("账号 {} 资料目录迁移到 {}", user.username(), expected);
            } catch (Exception e) {
                throw new IllegalStateException("迁移账号资料目录失败：" + user.username(), e);
            }
        }
    }

    public record User(long id, String username, String rawDir, boolean admin) {
    }

    public synchronized User register(String rawUsername, String password) {
        String username = normalize(rawUsername);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名只能包含中文、字母、数字、下划线或短横线，长度 2–32");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在");
        }
        Integer count = jdbc.queryForObject("select count(*) from app_user", Integer.class);
        boolean first = count == null || count == 0;
        long userId;
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt);
        // 所有账号都使用 raw/<用户名> 作为自己的资料根目录。
        String rawDir = "raw/" + username;
        if (first) {
            migrateLegacyRaw(rawDir);
        } else {
            try {
                Files.createDirectories(Path.of(rawDir).toAbsolutePath().normalize());
            } catch (Exception e) {
                throw new IllegalStateException("创建账号目录失败", e);
            }
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into app_user(username, password_hash, salt, is_admin, raw_dir, created_at)
                    values (?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setInt(4, first ? 1 : 0);
            ps.setString(5, rawDir);
            ps.setString(6, LocalDateTime.now().toString());
            return ps;
        }, key);
        Number id = key.getKey();
        if (id == null) {
            throw new IllegalStateException("创建账号失败");
        }
        userId = id.longValue();
        try {
            userDatabases.createIfNeeded(userId);
        } catch (RuntimeException e) {
            jdbc.update("delete from app_user where id=?", userId);
            throw e;
        }
        return new User(userId, username, rawDir, first);
    }

    public User login(String rawUsername, String password) {
        String username = normalize(rawUsername);
        List<UserRow> rows = jdbc.query("""
                select id, username, password_hash, salt, raw_dir, is_admin
                  from app_user where lower(username)=lower(?)
                """, (rs, i) -> new UserRow(rs.getLong("id"), rs.getString("username"),
                rs.getString("password_hash"), rs.getString("salt"),
                rs.getString("raw_dir"), rs.getInt("is_admin") != 0), username);
        if (rows.isEmpty() || !PasswordHasher.matches(password == null ? "" : password,
                rows.get(0).salt(), rows.get(0).hash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        UserRow row = rows.get(0);
        userDatabases.createIfNeeded(row.id());
        return new User(row.id(), row.username(), row.rawDir(), row.admin());
    }

    public User findById(long id) {
        List<User> users = jdbc.query("""
                select id, username, raw_dir, is_admin from app_user where id=?
                """, (rs, i) -> new User(rs.getLong("id"), rs.getString("username"),
                rs.getString("raw_dir"), rs.getInt("is_admin") != 0), id);
        return users.isEmpty() ? null : users.get(0);
    }

    /** 为小程序等非浏览器客户端签发长期 Token；数据库只保存哈希。 */
    public String issueToken(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                insert into app_token(token_hash, user_id, created_at, expires_at, last_used_at)
                values (?,?,?,?,?)
                """, sha256(token), user.id(), now.toString(),
                now.plusDays(TOKEN_DAYS).toString(), now.toString());
        return token;
    }

    public User findByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = sha256(token.strip());
        List<Long> ids = jdbc.query("""
                select user_id from app_token
                 where token_hash = ? and expires_at > ?
                """, (rs, i) -> rs.getLong(1), hash, LocalDateTime.now().toString());
        if (ids.isEmpty()) {
            return null;
        }
        jdbc.update("update app_token set last_used_at = ? where token_hash = ?",
                LocalDateTime.now().toString(), hash);
        return findById(ids.get(0));
    }

    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        jdbc.update("delete from app_token where token_hash = ?", sha256(token.strip()));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Token 摘要失败", e);
        }
    }

    public User findByUsername(String username) {
        List<User> users = jdbc.query("""
                select id, username, raw_dir, is_admin from app_user
                 where lower(username)=lower(?)
                """, (rs, i) -> new User(rs.getLong("id"), rs.getString("username"),
                rs.getString("raw_dir"), rs.getInt("is_admin") != 0), normalize(username));
        return users.isEmpty() ? null : users.get(0);
    }

    public int count() {
        Integer n = jdbc.queryForObject("select count(*) from app_user", Integer.class);
        return n == null ? 0 : n;
    }

    public CurrentUser.Principal principal(User user) {
        return new CurrentUser.Principal(user.id(), user.username(), user.rawDir(), user.admin());
    }

    /**
     * 第一个账号注册时，把旧版 raw/ 根目录里的资料移进 raw/<用户名>/，
     * 这样登录后能继续看到原来的资料，同时以后每个账号都有独立的目录。
     */
    private static void migrateLegacyRaw(String rawDir) {
        Path root = Path.of("raw").toAbsolutePath().normalize();
        Path target = Path.of(rawDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(target);
            if (!Files.isDirectory(root)) {
                return;
            }
            try (Stream<Path> stream = Files.list(root)) {
                for (Path child : stream.toList()) {
                    Path source = child.toAbsolutePath().normalize();
                    if (source.equals(target)) {
                        continue;
                    }
                    Path dest = target.resolve(child.getFileName().toString());
                    if (Files.exists(dest)) {
                        continue;
                    }
                    Files.move(source, dest);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("迁移旧资料目录失败", e);
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.strip();
    }

    private record UserRow(long id, String username, String hash, String salt,
                           String rawDir, boolean admin) {
    }

    private record RawDirRow(long id, String username, String rawDir) {
    }
}
