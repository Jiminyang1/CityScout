package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherOrderStatusDTO {
    private String id;
    private Long voucherId;
    private String voucherTitle;
    private Integer status;
    private String statusText;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime useTime;
    private LocalDateTime refundTime;
    private LocalDateTime updateTime;
}
