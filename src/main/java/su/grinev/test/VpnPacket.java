package su.grinev.test;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class VpnPacket {
    @Tag(0)
    private BigDecimal bigDecimal;
    @Tag(1)
    private String encoding;
    @Tag(2)
    private byte[] packet;
}
