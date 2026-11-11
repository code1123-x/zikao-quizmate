package com.quizmate.importer;

import com.quizmate.common.CurrentUser;
import com.quizmate.common.QuizmatePaths;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Picture;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把原始资料里内嵌的「答案扫描页」抽成图片，供补答案页面直接显示。
 */
@Slf4j
@Service
public class AnswerImageService {

    private static final int MIN_SIDE = 260;
    private static final int MAX_IMAGES = 12;

    private static Path root() {
        long userId = CurrentUser.id();
        return userId <= 1
                ? QuizmatePaths.data("answer-images")
                : QuizmatePaths.data("answer-images", "u" + userId);
    }

    private final SourceLocator locator;

    public AnswerImageService(SourceLocator locator) {
        this.locator = locator;
    }

    /** 返回可访问的相对 URL 列表，例如 /api/answer-images/ab12cd/0.jpg */
    public List<String> urls(String source) {
        List<Path> files = files(source);
        String key = key(source);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            out.add("/api/answer-images/" + key + "/" + files.get(i).getFileName());
        }
        return out;
    }

    /** 某套卷子的答案图片（缓存后的 jpg） */
    public List<Path> images(String source) {
        return files(source);
    }

    public Path fileByKey(String key, String name) {        if (key == null || !key.matches("[0-9a-f]{16}")) {
            return null;
        }
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        Path p = root().resolve(key).resolve(name);
        return Files.isRegularFile(p) ? p : null;
    }

    private synchronized List<Path> files(String source) {
        String key = key(source);
        Path dir = root().resolve(key);
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                List<Path> cached = s.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png");
                        })
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                if (!cached.isEmpty()) {
                    return cached;
                }
            } catch (Exception ignored) {
            }
        }

        Path src = locator.find(source);
        if (src == null) {
            return List.of();
        }
        List<BufferedImage> images;
        try {
            images = extract(src);
        } catch (Exception e) {
            log.warn("抽取图片失败 {}", src, e);
            return List.of();
        }

        List<Path> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            // 答案页通常是整页竖版扫描图，排在最前面；代码片段、插图放后面
            List<BufferedImage> ordered = new ArrayList<>(images);
            ordered.sort(Comparator.comparingInt(img -> pageLike(img) ? 0 : 1));
            int i = 0;
            for (BufferedImage img : ordered) {
                if (img == null || img.getWidth() < MIN_SIDE || img.getHeight() < MIN_SIDE) {
                    continue;
                }
                if (inkRatio(img) < 0.004) {
                    // 近乎空白页，多半是分隔页或封面，不是答案
                    continue;
                }
                Path f = dir.resolve(i + ".jpg");
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                var g = rgb.createGraphics();
                g.drawImage(img, 0, 0, java.awt.Color.WHITE, null);
                g.dispose();
                ImageIO.write(rgb, "jpg", f.toFile());
                out.add(f);
                if (++i >= MAX_IMAGES) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("写出答案图失败 {}", src, e);
        }
        return out;
    }

    private List<BufferedImage> extract(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return fromDocx(file);
        }
        if (name.endsWith(".doc")) {
            return fromDoc(file);
        }
        if (name.endsWith(".pdf")) {
            return fromPdf(file);
        }
        return List.of();
    }

    private List<BufferedImage> fromDoc(Path file) throws Exception {
        List<BufferedImage> out = new ArrayList<>();
        try (HWPFDocument doc = new HWPFDocument(new FileInputStream(file.toFile()))) {
            for (Picture p : doc.getPicturesTable().getAllPictures()) {
                BufferedImage img = read(p.getContent());
                if (img != null) {
                    out.add(img);
                }
            }
        }
        return out;
    }

    private List<BufferedImage> fromDocx(Path file) throws Exception {
        List<BufferedImage> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().startsWith("word/media/"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry e : entries) {
                try (InputStream in = zip.getInputStream(e)) {
                    BufferedImage img = read(in.readAllBytes());
                    if (img != null) {
                        out.add(img);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }

    private List<BufferedImage> fromPdf(Path file) throws Exception {
        List<BufferedImage> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                if (res == null) {
                    continue;
                }
                for (COSName cn : res.getXObjectNames()) {
                    PDXObject xo = res.getXObject(cn);
                    if (xo instanceof PDImageXObject img) {
                        try {
                            BufferedImage bi = img.getImage();
                            if (bi != null) {
                                out.add(bi);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        return out;
    }

    private BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /** 整页竖版扫描图（答案页）的特征 */
    private static boolean pageLike(BufferedImage img) {
        return img != null
                && img.getWidth() >= 450
                && img.getHeight() >= 700
                && img.getHeight() > img.getWidth();
    }

    /** 粗略估计「有多少墨」，用来过滤几乎空白的图片 */
    private static double inkRatio(BufferedImage img) {
        int stepX = Math.max(1, img.getWidth() / 160);
        int stepY = Math.max(1, img.getHeight() / 160);
        int dark = 0;
        int total = 0;
        for (int y = 0; y < img.getHeight(); y += stepY) {
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum < 190) {
                    dark++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) dark / total;
    }

    public static String key(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(("img2|" + CurrentUser.id() + "|" + source).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
