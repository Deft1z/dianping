package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 *  优惠券
 * </p>
 *
 */
public interface IVoucherService extends IService<Voucher> {

    //返回某个店铺的优惠券信息
    List<Voucher> queryVoucherOfShop(Long shopId);

    //新增秒杀券
    void addSeckillVoucher(Voucher voucher);
}
