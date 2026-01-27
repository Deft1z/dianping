package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  秒杀券下单服务类
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result createVoucherOrder(Long voucherId);
    void createVoucherOrder(VoucherOrder voucherOrder);
    Result seckillVoucher(Long voucherId);
}
