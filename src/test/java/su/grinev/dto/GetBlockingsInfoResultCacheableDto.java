package su.grinev.dto;

import annotation.Tag;

import java.io.Serializable;
import java.util.List;

public class GetBlockingsInfoResultCacheableDto implements Serializable {
    @Tag(2)
    private String customerId;
    @Tag(0)
    private String accountNumber;
    @Tag(1)
    private List<BlockingsInfoCacheableDto> blockingsInfo;

    public GetBlockingsInfoResultCacheableDto() {}

    public GetBlockingsInfoResultCacheableDto(String customerId, String accountNumber,
                                              List<BlockingsInfoCacheableDto> blockingsInfo) {
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.blockingsInfo = blockingsInfo;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public List<BlockingsInfoCacheableDto> getBlockingsInfo() { return blockingsInfo; }
    public void setBlockingsInfo(List<BlockingsInfoCacheableDto> blockingsInfo) { this.blockingsInfo = blockingsInfo; }
}
