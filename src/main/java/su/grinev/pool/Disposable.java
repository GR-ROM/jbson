package su.grinev.pool;

public interface Disposable extends AutoCloseable {

    void setOnDispose(Runnable onDispose);
    void dispose();

}
