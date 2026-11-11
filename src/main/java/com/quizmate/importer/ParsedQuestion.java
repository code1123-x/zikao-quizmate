package com.quizmate.importer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ParsedQuestion {
    private int number;
    private String type;
    private String stem = "";
    private List<ParsedOption> options = new ArrayList<>();
    private String answer;
    private String analysis;
    private List<String> tags = new ArrayList<>();

    public void addStem(String line) {
        if (!stem.isEmpty()) {
            stem += "\n";
        }
        stem += line;
    }

    public void addAnalysis(String line) {
        if (analysis == null || analysis.isEmpty()) {
            analysis = line;
        } else {
            analysis += "\n" + line;
        }
    }

    public boolean hasOptions() {
        return options.size() >= 2;
    }

    @Data
    public static class ParsedOption {
        private final String key;
        private String text;

        public ParsedOption(String key, String text) {
            this.key = key;
            this.text = text;
        }

        public void append(String more) {
            if (more == null || more.isBlank()) {
                return;
            }
            text = text == null || text.isBlank() ? more : text + " " + more;
        }
    }
}
