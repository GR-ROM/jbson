package su.grinev;

import lombok.Getter;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Getter
public class BinaryDocument {

    private final Map<Object, Object> documentMap;
    private final int length;

    public BinaryDocument(Map<Object, Object> documentMap, int length) {
        this.documentMap = documentMap;
        this.length = length;
    }

    public BinaryDocument(Map<Object, Object> documentMap) {
        this.documentMap = documentMap;
        this.length = 0;
    }

    public Object get(String key) {
        List<Integer> path = Arrays.stream(key.split("\\."))
                .map(Integer::parseInt).toList();

        Iterator<Integer> i = path.iterator();
        Map<Object, Object> map = documentMap;
        while (i.hasNext()) {
            Object o = map.get(i.next());
            if (i.hasNext()) {
                map = (Map<Object, Object>) o;
            } else {
                return o;
            }
        }

        return null;
    }

}
