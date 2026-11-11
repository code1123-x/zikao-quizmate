package com.quizmate.importer;

import com.quizmate.common.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 用「文件名（不含扩展名）」反查 raw/ 下的原始资料。
 * 题目里只存了 source = 文件名，没存路径。
 */
@Slf4j
@Component
public class SourceLocator {

    private static final Set<String> PREFERRED = Set.of(".docx", ".doc", ".pdf");
    /** 只去掉真正的文档扩展名。名字里的「2018.4」这种考期不能被当成扩展名去掉 */
    private static final String[] EXTS = {".docx", ".doc", ".pdf", ".txt", ".md", ".pptx", ".xlsx", ".xls", ".wps", ".rtf"};

    private final Map<Long, Map<String, Path>> indexes = new ConcurrentHashMap<>();

    public Path find(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Map<String, Path> map = index();
        return map.get(normalize(source));
    }

    private Map<String, Path> index() {
        long userId = CurrentUser.id();
        return indexes.computeIfAbsent(userId, id -> build());
    }

    private Map<String, Path> build() {
        Map<String, Path> built = new HashMap<>();
        Path raw = CurrentUser.rawDir();
        if (Files.isDirectory(raw)) {
            try (Stream<Path> stream = Files.walk(raw)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                for (Path p : files) {
                    String name = p.getFileName().toString();
                    String key = normalize(name);
                    Path old = built.get(key);
                    if (old == null || better(p, old)) {
                        built.put(key, p);
                    }
                }
            } catch (IOException e) {
                log.warn("扫描账号资料目录失败 user={}", CurrentUser.id(), e);
            }
        }
        log.info("账号资料索引完成 user={}：{} 个文件", CurrentUser.id(), built.size());
        return built;
    }

    /** 同名文件优先取 docx/doc/pdf，其次体积大的 */
    private boolean better(Path candidate, Path current) {
        String ce = ext(candidate.getFileName().toString());
        String oe = ext(current.getFileName().toString());
        boolean cp = PREFERRED.contains(ce);
        boolean op = PREFERRED.contains(oe);
        if (cp != op) {
            return cp;
        }
        try {
            return Files.size(candidate) > Files.size(current);
        } catch (IOException e) {
            return false;
        }
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase(Locale.ROOT);
    }

    public static String normalize(String name) {
        String s = name.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        for (String e : EXTS) {
            if (lower.endsWith(e)) {
                s = s.substring(0, s.length() - e.length());
                break;
            }
        }
        return s.toLowerCase(Locale.ROOT);
    }
}
