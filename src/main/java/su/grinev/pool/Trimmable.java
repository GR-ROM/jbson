package su.grinev.pool;

public interface Trimmable {
    String getName();
    int getCount();
    int getIdle();
    boolean trim(int size);
    boolean isTrimmable();
}
