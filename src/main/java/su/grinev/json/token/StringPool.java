package su.grinev.json.token;

import java.util.HashMap;
import java.util.Map;

public class StringPool {
    private final Map<String, String> pool = new HashMap<>();

    public String intern(String s) {
        String existing = pool.get(s);
        if (existing != null) return existing;
        pool.put(s, s);
        return s;
    }
}

