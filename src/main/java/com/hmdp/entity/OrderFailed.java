package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("tb_order_failed")
public class OrderFailed implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "order_id", type = IdType.INPUT)
    private Long orderId;

    private Long userId;

    private Long voucherId;

    private String requestId;

    private String payload;

    private String reason;

    private LocalDateTime createTime;
}
