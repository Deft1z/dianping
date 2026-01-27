package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 *
 * 秒杀券订单处理
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;
    //代理对象
    private  IVoucherOrderService proxy;
    //阻塞队列
    private BlockingQueue<VoucherOrder>orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //使用@PostConstruct注解:在当前类初始化完毕后执行init方法(因为服务启动，用户就有可能抢券，阻塞队列必须在下单以前就启动)
    //init方法:线程池提交线程任务
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    //①最基础的抢券下单
    public Result seckillVoucherBasic(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀未开始!");
        }
        //3.判断秒杀是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束！");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("秒杀券库存不足！");
        }
        //5.减少库存 【超卖问题 加乐观锁 CAS方式实现】
        boolean success = seckillVoucherService.update()
                .eq("voucher_id",voucherId)//where id = voucherId
                //.eq("stock",voucher.getStock())//5.1 加乐观锁  and stock = ? 操作库存必须和一开始查询出来的数据一致
                .gt("stock",0)       //5.2 优化乐观锁 and stock > 0 只需要库存大于0 即可购买 提高请求的成功率
                .setSql("stock = stock - 1")     //set stock -= 1
                .update();
        if(!success){
            return Result.fail("秒杀失败!");
        }

        //6.1 【悲观锁优化，解决单体环境下一人一单的问题】
        //如果只使用userId.toString() 返回的是一个对象 每次调用得到对象不一样 锁无法生效
        //调用intern，从常量池中找到这样的值的String串返回，所以如果用户的id一样，得到的String对象就一样，起到对这个String加锁的效果
        Long userId = UserHolder.getUser().getId();
//        synchronized ((userId.toString()).intern()) {
//                //获取代理对象
//                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.createVoucherOrder(voucherId);
//        }//事务提交后才释放锁

        //6.2 【分布式锁优化，解决集群环境下一人一单的问题】
        //【基于Redis的分布式锁】创建锁对象 锁名称为业务名称+用户id
        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
        //【基于Redision的分布式锁】创建锁对象 锁名称为业务名称+用户id
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获取失败,返回错误/重试
            return Result.fail("不允许重复下单！");
        }
        try{
            //获取代理对象来避免事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }
    }


    //②优化秒杀的创建订单方法，解决一人一单和超卖问题
    //将购买资格判断 扣减库存 创建订单封装起来，作为一个事务
    @Transactional
    public Result createVoucherOrder(Long voucherId){
        //获取用户ID
        Long userId = UserHolder.getUser().getId();

        // 不能在这里synchronized ((userId.toString()).intern()) {XXXX} 的原因
        // 代码执行后释放锁，再由Spring提交事务；而释放锁以后，其他线程就可以进入
        // 可能出现并发问题，所以锁加在整个函数外面

        //查询订单
        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //判断是否存在
        if (count > 0) {
            //用户已经买过了
            log.error("用户已经抢购过！");
        }
        //6.扣减库存  Mysql数据库
        boolean success = seckillVoucherService.update()
                .eq("voucher_id",voucherId)
                .gt("stock",0) //where id = ? and stock > 0
                .setSql("stock = stock - 1")//set stock = stock -1
                .update();
        if (!success){
            log.error("购买失败，库存不足！");
        }
        //7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单ID 用户ID 秒杀券ID
        Long orderId = redisIdWorker.nextId("Order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        this.save(voucherOrder);
        //8.返回结果
        return Result.ok(voucherOrder.getId());
    }



    //引入LUA脚本 seckill.lua
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //③优化秒杀:LUA脚本判断下单资格 + 阻塞队列异步优化
    @Transactional
    public Result seckillVoucherByBlockingQueue(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();

        //1.执行lua脚本 参数【优惠券id 用户id 订单id】
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
                , Collections.emptyList()
                ,voucherId.toString(),userId.toString());

        //2.判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //2.1 r不为0 没有购买资格 r = 1 库存不足 r = 2 用户下过单了，不能重复下单
            return Result.fail(r == 1?"库存不足" : "不能重复下单");
        }

        //2.2为0 有购买资格 把下单信息保存到阻塞队列
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.获取代理对象（通过独立线程无法获取这个代理对象所以要在这里获取）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回结果
        return Result.ok(orderId);
    }

    //④阻塞队列线程任务
    private class VoucherOrderHandlerByBlockingQueue implements Runnable{
        @Override
        public void run(){
            //死循环
            while(true){
                try {
                    //1.获取队列中的订单信息 take是一个阻塞方法
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2.将订单信息存入数据库
                    createVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }


    //⑤优化秒杀:LUA脚本判断下单资格 + Stream队列异步优化
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //获取用户id
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        Long orderId = redisIdWorker.nextId("order");

        //1.执行lua脚本 参数【优惠券id 用户id 订单id】
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT
                , Collections.emptyList()
                ,voucherId.toString(),userId.toString(),String.valueOf(orderId));

        //2.判断结果是否为0
        int r = result.intValue();
        if(r != 0){
            //2.1 r不为0 没有购买资格 r = 1 库存不足 r = 2 用户下过单了，不能重复下单
            return Result.fail(r == 1?"库存不足" : "不能重复下单");
        }

        //3.获取代理对象（通过独立线程无法获取这个代理对象所以要在这里获取）
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回结果
        return Result.ok(orderId);
    }

    //⑥基于Stream消息队列处理任务
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run(){
            String queueName = "stream.orders";
            //死循环
            while(true){
                try {
                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1 STREAMS stream.orders >
                    List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if(list==null||list.isEmpty()){
                        //如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    //3.如果获取成功，可以下单,解析消息中的订单信息
                    MapRecord<String,Object,Object>record = list.get(0);
                    Map<Object,Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                    createVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.orders g1 id(消息ID）
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    //出现异常 说明没有被ACK确认 就要到PENDING list中处理异常
                    try {
                        handlePendingList();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    //⑦ 处理Pendinglist的异常消息
    private void handlePendingList() throws InterruptedException {
        String queueName = "stream.orders";
        //死循环
        while(true){
            try {
                //1.获取Pending list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 1 STREAMS stream.orders 0
                List<MapRecord<String,Object,Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1","c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //2.判断消息是否获取成功
                if(list==null||list.isEmpty()){
                    //2.1如果获取失败，说明pending list没有异常消息，结束循环
                    break;
                }
                //3.如果获取成功，可以下单,解析消息中的订单信息
                MapRecord<String,Object,Object>record = list.get(0);
                Map<Object,Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values,new VoucherOrder(),true);
                //下单
                createVoucherOrder(voucherOrder);
                //4.ACK确认 SACK命令 SACK stream.orders g1 id(消息ID） record.getId()是消息ID
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
            } catch (Exception e) {
                //如果处理过程中又出现异常 需要调用自己 不需要递归 继续下次循环
                Thread.sleep(3000);
                //log.error("处理Pending-List异常");
            }
        }
    }


    //异步处理的创建秒杀订单到数据库
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {

//        //理论上不需要这部分一人一单和超卖的判断
//        //获取用户ID
//        Long userId = UserHolder.getUser().getId();
//        //查询订单
//        Integer count = this.query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
//        //判断是否存在
//        if (count > 0) {
//            //用户已经买过了
//            log.error("用户已经抢购过！");
//        }
//        //扣减库存  Mysql数据库
//        boolean success = seckillVoucherService.update()
//                .eq("voucher_id",voucherOrder.getVoucherId())
//                .gt("stock",0) //where id = ? and stock > 0
//                .setSql("stock = stock - 1")//set stock = stock -1
//                .update();
//        if (!success){
//            log.error("购买失败，库存不足！");
//        }

        //7. 创建订单
        this.save(voucherOrder);
        //无需返回 因为是异步线程处理
    }

//    /*
//       创建订单到数据库
//     */
//    private void handleVoucherOrder(VoucherOrder voucherOrder) {
////        //获取用户 不能从Userholder中获取 因为是单独的线程
////        Long userId = voucherOrder.getUserId();
////
////        //理论上这里不需要锁，只是做兜底
////        //创建分布式锁对象
////        RLock lock = redissonClient.getLock("lock:order:"+userId);
////        //获取锁
////        boolean isLock = lock.tryLock();
////        if(!isLock){
////            //异步处理 不用返回给前端 没有意义
////            log.error("不允许重复下单!");
////            return;
////        }
////        try{
////           //获取代理对象
////            //作为子线程 不可能拿到代理 要用代理来实现事务 就要从父线程中获取代理
////            //所以设置为全局变量
////            proxy.createVoucherOrder(voucherOrder);
////        }finally {
////            lock.unlock();
////        }
//        createVoucherOrder(voucherOrder);
//
//    }





}
