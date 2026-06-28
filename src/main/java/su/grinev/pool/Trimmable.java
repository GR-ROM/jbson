package su.grinev.pool;

public interface Trimmable {
    String getName();
    int getCountInUse();
    int getIdle();
    int getMinSize();
    boolean trim(int size);
    boolean isTrimmable();
}
