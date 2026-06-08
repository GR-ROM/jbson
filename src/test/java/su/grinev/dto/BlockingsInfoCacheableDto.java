package su.grinev.dto;

import annotation.Tag;

import java.io.Serializable;

public class BlockingsInfoCacheableDto implements Serializable {

    @Tag(5)
    private Integer number;
    @Tag(4)
    private String date;
    @Tag(0)
    private String authorityName;
    @Tag(2)
    private String blockReason;
    @Tag(1)
    private Long blockAmount;
    @Tag(3)
    private String blockType;

    public BlockingsInfoCacheableDto() {}

    public BlockingsInfoCacheableDto(Integer number, String date, String authorityName,
                                     String blockReason, Long blockAmount, String blockType) {
        this.number = number;
        this.date = date;
        this.authorityName = authorityName;
        this.blockReason = blockReason;
        this.blockAmount = blockAmount;
        this.blockType = blockType;
    }

    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public String getAuthorityName() { return authorityName; }
    public void setAuthorityName(String authorityName) { this.authorityName = authorityName; }

    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }

    public Long getBlockAmount() { return blockAmount; }
    public void setBlockAmount(Long blockAmount) { this.blockAmount = blockAmount; }

    public String getBlockType() { return blockType; }
    public void setBlockType(String blockType) { this.blockType = blockType; }
}
