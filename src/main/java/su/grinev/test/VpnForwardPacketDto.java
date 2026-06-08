package su.grinev.test;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VpnForwardPacketDto {
    @Tag(0)
    private ByteBuffer packet;
}