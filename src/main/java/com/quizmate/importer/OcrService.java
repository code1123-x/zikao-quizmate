package com.quizmate.importer;

import com.quizmate.common.QuizmatePaths;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 用 Tesseract 识别答案扫描页。只认数字和 A-F，中文一律当噪声。
 * 首次调用会把 classpath 里的 eng.traineddata 释放到 data/tessdata。
 */
@Slf4j
@Service
public class OcrService {

    public record Token(String text, int x, int y, int w, int h) {
    }

    private final Tesseract tesseract = new Tesseract();
    private final Object lock = new Object();
    private volatile boolean ready;

    public OcrService() {
        tesseract.setLanguage("eng");
        tesseract.setVariable("tessedit_char_whitelist", "ABCDEF0123456789.");
        tesseract.setVariable("preserve_interword_spaces", "1");
        tesseract.setVariable("user_defined_dpi", "300");
        tesseract.setVariable("debug_file", "nul");
    }

    public boolean available() {
        return true;
    }

    /** psm: 6 = 整块文本，4 = 单列可变行，两种结果交叉验证 */
    public List<Token> tokens(BufferedImage image, int psm) {
        ensureData();
        synchronized (lock) {
            try {
                tesseract.setPageSegMode(psm);
                List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
                List<Token> out = new ArrayList<>();
                for (Word w : words) {
                    Rectangle r = w.getBoundingBox();
                    if (r == null || w.getText() == null) {
                        continue;
                    }
                    String t = w.getText().replaceAll("\\s+", "");
                    if (t.isEmpty()) {
                        continue;
                    }
                    out.add(new Token(t, r.x, r.y, r.width, r.height));
                }
                return out;
            } catch (Exception e) {
                log.warn("OCR 失败（psm={}）", psm, e);
                return List.of();
            }
        }
    }

    public BufferedImage readRaw(Path file) {
        try {
            return ImageIO.read(file.toFile());
        } catch (Exception e) {
            return null;
        }
    }

    public BufferedImage read(Path file) {
        BufferedImage raw = readRaw(file);
        return raw == null ? null : clean(raw);
    }

    /**
     * 预处理：去掉彩色水印（微信/网站水印大多是青绿色），再拉对比度。
     * 这一步对水印盖住答案行的页面提升非常明显。
     */
    public static BufferedImage clean(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int rgb = px[i];
            int r = (rgb >> 16) & 0xff;
            int g = (rgb >> 8) & 0xff;
            int b = rgb & 0xff;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            double sat = max == 0 ? 0 : (double) (max - min) / max;
            int gray = (r * 299 + g * 587 + b * 114) / 1000;
            if (sat > 0.16 && max > 40) {
                gray = 255;
            } else if (gray > 150) {
                gray = 255;
            } else if (gray < 90) {
                gray = 0;
            } else {
                gray = (gray - 90) * 255 / 60;
            }
            px[i] = (gray << 16) | (gray << 8) | gray;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    private void ensureData() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            Path dir = QuizmatePaths.data("tessdata");
            Path file = dir.resolve("eng.traineddata");
            if (!Files.exists(file)) {
                try (InputStream in = new ClassPathResource("tessdata/eng.traineddata").getInputStream()) {
                    Files.createDirectories(dir);
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                    log.info("已释放 OCR 语言包到 {}", file);
                } catch (Exception e) {
                    log.warn("释放 OCR 语言包失败", e);
                }
            }
            tesseract.setDatapath(dir.toAbsolutePath().toString());
            ready = true;
        }
    }
}
