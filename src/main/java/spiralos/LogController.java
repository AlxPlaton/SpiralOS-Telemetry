package spiralos;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import spiralos.LogBuffer.Entry;

/**
 * Serves SpiralOS' own log ("app") plus any server log files the OPERATOR lists in
 * application.properties, e.g.  spiralos.logs.files=pihole=/var/log/pihole/pihole.log
 * Clients can only pick a source id; they can never supply a path.
 */
@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);
    private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Set<String> LEVELS = Set.of("ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final long MAX_TAIL_BYTES = 256 * 1024;

    private final LogBuffer buffer;
    private final Map<String, Path> files = new LinkedHashMap<>();

    public LogController(LogBuffer buffer, @Value("${spiralos.logs.files:}") String filesSpec) {
        this.buffer = buffer;
        for (String part : filesSpec.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 1) {
                continue;
            }
            String id = part.substring(0, eq).trim();
            String path = part.substring(eq + 1).trim();
            try {
                if (SOURCE_ID.matcher(id).matches() && !id.equals("app") && !path.isEmpty()) {
                    files.put(id, Path.of(path));
                }
            } catch (InvalidPathException e) {
                log.warn("Ignoring log source '{}': invalid path", id);
            }
        }
    }

    @GetMapping("/sources")
    public List<Map<String, String>> sources() {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of("id", "app", "label", "SpiralOS application"));
        files.keySet().forEach(id -> out.add(Map.of("id", id, "label", id)));
        return out;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> read(
            @RequestParam(defaultValue = "app") String source,
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(defaultValue = "ALL") String level,
            @RequestParam(defaultValue = "") String q) {

        if (lines < 1 || lines > 1000) {
            return bad(400, "lines must be between 1 and 1000");
        }
        if (q.length() > 100) {
            return bad(400, "search text is too long");
        }
        if (!LEVELS.contains(level)) {
            return bad(400, "unknown level");
        }
        String needle = q.toLowerCase(Locale.ROOT).trim();
        List<Entry> result;

        if (source.equals("app")) {
            int min = level.equals("ALL") ? 0 : rank(level);
            result = buffer.snapshot().stream()
                    .filter(e -> rank(e.level()) >= min)
                    .filter(e -> needle.isEmpty() || e.message().toLowerCase(Locale.ROOT).contains(needle)
                            || e.logger().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        } else {
            Path path = SOURCE_ID.matcher(source).matches() ? files.get(source) : null;
            if (path == null) {
                return bad(400, "unknown log source");
            }
            try {
                result = tail(path, 1000).stream()
                        .filter(l -> needle.isEmpty() || l.toLowerCase(Locale.ROOT).contains(needle))
                        .map(l -> new Entry(null, null, null, l))
                        .toList();
            } catch (IOException e) {
                log.warn("Cannot read log source '{}': {}", source, e.getClass().getSimpleName());
                return bad(503, "That log file is not readable by the SpiralOS process");
            }
        }

        List<Entry> page = result.subList(Math.max(0, result.size() - lines), result.size());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("count", page.size());
        body.put("lines", page);
        return ResponseEntity.ok(body);
    }

    private static int rank(String level) {
        return switch (level) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "INFO" -> 2;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            default -> 2;
        };
    }

    private static List<String> tail(Path path, int maxLines) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(0, len - MAX_TAIL_BYTES);
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            List<String> all = new ArrayList<>(Arrays.asList(new String(buf, StandardCharsets.UTF_8).split("\\R")));
            if (start > 0 && !all.isEmpty()) {
                all.remove(0); // first line is probably cut in half
            }
            all.removeIf(String::isBlank);
            List<String> last = all.subList(Math.max(0, all.size() - maxLines), all.size());
            return last.stream().map(s -> s.length() > 1000 ? s.substring(0, 1000) + "…" : s).toList();
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
