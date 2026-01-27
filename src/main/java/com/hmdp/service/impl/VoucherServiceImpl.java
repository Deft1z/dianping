package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 *  优惠券 服务实现类
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询某个店铺的所有优惠券券
     * @param shopId
     * @return
     */
    @Override
    public List<Voucher> queryVoucherOfShop(Long shopId) {
        // 查询某个店铺的优惠券信息
        LambdaQueryWrapper<Voucher>queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Voucher::getShopId,shopId);
        List<Voucher> list = this.list(queryWrapper);

        //秒杀券补充字段
        for(Voucher voucher : list){
            //如果是秒杀券 填充字段: 数量 开始时间 结束时间
            if(voucher.getType() == 1){
                SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucher.getId());
                voucher.setStock(seckillVoucher.getStock());
                voucher.setBeginTime(seckillVoucher.getBeginTime());
                voucher.setEndTime(seckillVoucher.getEndTime());
            }
        }

        // 返回结果
        return list;
    }

    /**
     *  新增秒杀券
     */
    @Override
    @Transactional //开启事务
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);

        // 保存秒杀券
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        //保存秒杀库存到redis 【优化时的第一步】
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(),voucher.getStock().toString());
    }

}

