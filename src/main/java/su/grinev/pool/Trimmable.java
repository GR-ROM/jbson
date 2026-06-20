package su.grinev.pool;

public interface Trimmable {
    int getCount();
    int getIdle();
    boolean trim(int size);
    boolean isTrimmable();
}
