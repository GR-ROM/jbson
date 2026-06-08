package su.grinev.bson;

import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Setter
@Accessors(chain = true)
@NoArgsConstructor
public final class WriterContext {
    int lengthPos = 0;
    int startPos = -1;  // -1 means uninitialized

    // For documents: iterator over map entries
    Iterator<Map.Entry<Object, Object>> mapIterator;

    // For arrays: list and current index
    List<Object> arrayList;
    int arrayIndex;

    boolean isArray;

    public static WriterContext fillForDocument(
            WriterContext writerContext,
            int lengthPos,
            Map<Object, Object> value
    ) {
        writerContext.lengthPos = lengthPos;
        writerContext.mapIterator = value.entrySet().iterator();
        writerContext.arrayList = null;
        writerContext.arrayIndex = 0;
        writerContext.isArray = false;
        return writerContext;
    }

    public static WriterContext fillForArray(
            WriterContext writerContext,
            int lengthPos,
            List<Object> value
    ) {
        writerContext.lengthPos = lengthPos;
        writerContext.mapIterator = null;
        writerContext.arrayList = value;
        writerContext.arrayIndex = 0;
        writerContext.isArray = true;
        return writerContext;
    }

    public boolean hasNext() {
        if (isArray) {
            return arrayIndex < arrayList.size();
        } else {
            return mapIterator.hasNext();
        }
    }

    public int nextArrayIndex() {
        return arrayIndex++;
    }

    public static class NullObject {
        public static final NullObject INSTANCE = new NullObject();
        private NullObject() {}
    }
}
