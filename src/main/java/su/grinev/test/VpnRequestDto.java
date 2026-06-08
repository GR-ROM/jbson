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
public class VpnRequestDto<T> {

    @Tag(2)
    private String protocolVersion;
    @Tag(0)
    private Command command;
    @Tag(3)
    private Instant timestamp;

    @Tag(1)
    @Type(discriminator = 1488)
    private T data;

    public static <T> VpnRequestDto<T> wrap(Command command, T data) {
        return VpnRequestDto.<T>builder()
                .protocolVersion("0.1")
                .command(command)
                .data(data)
                .timestamp(Instant.now())
                .build();
    }
}

