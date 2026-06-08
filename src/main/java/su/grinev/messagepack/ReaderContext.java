package su.grinev.messagepack;

import java.util.List;
import java.util.Map;

public class ReaderContext {

    public Map<Object, Object> objectMap;
    public List<Object> array;
    public boolean isArray;
    public int size;
    public int index;

    public ReaderContext initMap(Map<Object, Object> objectMap, int size) {
        this.objectMap = objectMap;
        this.array = null;
        this.isArray = false;
        this.size = size;
        this.index = 0;
        return this;
    }

    public ReaderContext initArray(List<Object> objectList, int size) {
        this.objectMap = null;
        this.array = objectList;
        this.isArray = true;
        this.size = size;
        this.index = 0;
        return this;
    }
}
