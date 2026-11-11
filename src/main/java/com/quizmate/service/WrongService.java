package com.quizmate.service;

import com.quizmate.common.Json;
import com.quizmate.dto.Dtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 错题本：最近一次作答是错的题（答对之后自动离开错题本）。
 */
@Service
public class WrongService {

    private static final String LAST_IS_WRONG = """
            (select a.is_correct from answer_record a
              where a.question_id = q.id order by a.id desc limit 1) = 0""";

    private static final String CHOICE_ONLY = "q.type in ('SINGLE','MULTIPLE','JUDGE')";

    private final JdbcTemplate jdbc;

    public WrongService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Dtos.WrongSubject> summary(Long majorId) {
        String majorWhere = majorId == null ? "" : " and s.major_id = " + majorId;
        return jdbc.query("""
                select s.id, s.name, s.color, count(*) as c
                  from subject s
                  join question q on q.subject_id = s.id
                 where q.status = 'ACTIVE' and
                """ + LAST_IS_WRONG + " and " + CHOICE_ONLY + majorWhere + """
                 group by s.id, s.name, s.color
                 order by c desc, s.sort
                """, (rs, i) -> new Dtos.WrongSubject(
                rs.getLong("id"), rs.getString("name"), rs.getString("color"), rs.getInt("c")));
    }

    public Dtos.WrongPage list(Long subjectId, String type, String keyword, String sort,
                                int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder("""
                 where q.status = 'ACTIVE'
                   and q.subject_id = ?
                   and
                """);
        where.append(LAST_IS_WRONG).append(" and ").append(CHOICE_ONLY);
        List<Object> args = new ArrayList<>();
        args.add(subjectId);
        if (type != null && !type.isBlank()) {
            where.append(" and q.type = ?");
            args.add(type);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (q.stem like ? or q.answer like ? or q.source like ?)");
            String like = "%" + keyword.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Integer total = jdbc.queryForObject("select count(*) from question q" + where,
                Integer.class, args.toArray());
        String order = switch (sort == null ? "recent" : sort) {
            case "count" -> """
                    (select count(*) from answer_record a
                      where a.question_id = q.id and a.is_correct = 0) desc, q.id desc
                    """;
            case "oldest" -> """
                    (select max(a.answered_at) from answer_record a
                      where a.question_id = q.id and a.is_correct = 0) asc, q.id asc
                    """;
            case "type" -> "q.type asc, q.id desc";
            default -> """
                    (select max(a.answered_at) from answer_record a
                      where a.question_id = q.id and a.is_correct = 0) desc, q.id desc
                    """;
        };
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Long> ids = jdbc.queryForList(
                "select q.id from question q" + where + " order by " + order + " limit ? offset ?",
                Long.class, pageArgs.toArray());

        List<Dtos.WrongItem> items = new ArrayList<>();
        for (Long id : ids) {
            jdbc.query("""
                    select q.id, q.type, q.stem, q.options, q.answer, q.analysis, q.source, q.year,
                           (select count(*) from answer_record a
                             where a.question_id = q.id and a.is_correct = 0) as wc,
                           (select max(a.answered_at) from answer_record a
                             where a.question_id = q.id and a.is_correct = 0) as lw
                      from question q where q.id = ?
                    """, rs -> {
                List<Dtos.Option> options = new ArrayList<>();
                String raw = rs.getString("options");
                if (raw != null && !raw.isBlank()) {
                    options = List.of(Json.read(raw, Dtos.Option[].class));
                }
                items.add(new Dtos.WrongItem(
                        rs.getLong("id"), rs.getString("type"), rs.getString("stem"), options,
                        rs.getString("answer"), rs.getString("analysis"), rs.getString("source"),
                        (Integer) rs.getObject("year"), rs.getInt("wc"), rs.getString("lw")));
            }, id);
        }
        return new Dtos.WrongPage(items, total == null ? 0 : total, p, s);
    }
}
