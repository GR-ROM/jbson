package su.grinev.messagepack;

import java.util.Iterator;
import java.util.Map;

public class WriterContext {

    public Iterator<Map.Entry<Object, Object>> objectMap;
    public Iterator<Object> array;
    public boolean isArray;

    public WriterContext initMap(Iterator<Map.Entry<Object, Object>> objectMap) {
        this.objectMap = objectMap;
        this.isArray = false;
        return this;
    }

    public WriterContext initList(Iterator<Object> arrayMap) {
        this.array = arrayMap;
        this.isArray = true;
        return this;
    }
}
