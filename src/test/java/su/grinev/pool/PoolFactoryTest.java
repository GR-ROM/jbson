package su.grinev.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PoolFactoryTest {

    private static PoolFactory factory() {
        return PoolFactory.Builder.builder()
                .setMinPoolSize(10)
                .setMaxPoolSize(1000)
                .setBlocking(false)
                .setOutOfPoolTimeout(0)
                .build();
    }

    @Test
    void getPool_defaultsToFactoryMinPoolSize() {
        FastPool<Object> p = factory().getPool("p", Object::new);
        assertEquals(10, p.getIdle(), "default prefill = factory minPoolSize");
    }

    @Test
    void getPool_usesPerPoolPrefill() {
        FastPool<Object> p = factory().getPool("p", Object::new, 3);
        assertEquals(3, p.getIdle(), "prefilled to the explicit initialSize");
    }

    @Test
    void getDisposablePool_usesPerPoolPrefill() {
        DisposablePool<ArenaByteBuffer> p = factory().getDisposablePool("d", () -> new ArenaByteBuffer(64), 5);
        assertEquals(5, p.getIdle(), "disposable pool prefilled to the explicit initialSize");
    }
}
