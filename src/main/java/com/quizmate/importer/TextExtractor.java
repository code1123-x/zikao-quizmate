package com.quizmate.importer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

@Component
public class TextExtractor {

    public record Extracted(String text, int pages, boolean scanned) {
    }

    public Extracted extract(File file) throws Exception {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return docx(file);
        }
        if (name.endsWith(".doc")) {
            return doc(file);
        }
        if (name.endsWith(".pdf")) {
            return pdf(file);
        }
        if (name.endsWith(".pptx")) {
            return pptx(file);
        }
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            String t = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            return new Extracted(t, 0, false);
        }
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            return excel(file);
        }
        return new Extracted("", 0, false);
    }

    private Extracted docx(File file) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(file.toPath()));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            String text = ex.getText();
            return new Extracted(text == null ? "" : text, 0, false);
        }
    }

    private Extracted doc(File file) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(Files.newInputStream(file.toPath()))) {
            // 直接用 Range 取文本：避开 WordExtractor 的转换器（会打大量无意义日志，个别文件还会抛 NPE）
            String text = doc.getRange().text();
            if (text == null) {
                text = "";
            }
            text = text.replace('\r', '\n')
                    .replaceAll("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f]", "");
            return new Extracted(text, 0, false);
        }
    }

    private Extracted pdf(File file) throws Exception {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null) {
                text = "";
            }
            // 去掉不可见字符后判断文字层
            String visible = text.replaceAll("[\\s\\u0000-\\u001f]", "");
            return new Extracted(text, doc.getNumberOfPages(), visible.length() < 20);
        }
    }

    private Extracted pptx(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(file.toPath()))) {
            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t.replace("\r", "")).append('\n');
                        }
                    }
                }
                sb.append('\n');
            }
            return new Extracted(sb.toString(), ppt.getSlides().size(), false);
        }
    }

    private Extracted excel(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(file)) {
            for (Sheet sheet : wb) {
                sb.append("### ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        String v = cell.toString().strip();
                        if (v.isEmpty()) {
                            continue;
                        }
                        if (!line.isEmpty()) {
                            line.append(" | ");
                        }
                        line.append(v);
                    }
                    if (!line.isEmpty()) {
                        sb.append(line).append('\n');
                    }
                }
            }
            return new Extracted(sb.toString(), 0, false);
        }
    }
}
