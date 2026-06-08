package su.grinev.test;

import annotation.Type;
import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VpnResponseDto<T> {

    @Tag(1)
    private String protocolVersion;
    @Tag(2)
    private Status status;
    @Tag(3)
    private Instant timestamp;
    @Tag(0)
    @Type(discriminator = 1488)
    private T data;

    public static <T> VpnResponseDto<T> wrap(Status status, T data) {
        return VpnResponseDto.<T>builder()
                .protocolVersion("0.1")
                .status(status)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }
}


