package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author yif
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 一开始就执行
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherHandler());
    }

    private class VoucherHandler implements Runnable {
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                // 获取队列中的订单信息
                try {
                    // 获取消息队列中的信息
//                    VoucherOrder voucherOrder = orderTasks.take();
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 如果失败 表示没有消息，继续下一次循环
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("g1", queueName, record.getId());
                    // 创建订单
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        // 如果失败 表示没有消息，继续下一次循环
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    // ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("g1", queueName, record.getId());
                    // 创建订单
                } catch (Exception e) {
                    log.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }
    }



    /**
     * 作为成员变量
     */
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order:");
        // 1. 执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2. 判断结果为0
        if (r != 0) {
            // 2.1 不为0， 代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 获取用户
//        Long userId = UserHolder.getUser().getId();
//        // 1. 执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//        int r = result.intValue();
//        // 2. 判断结果为0
//        if (r != 0) {
//            // 2.1 不为0， 代表没有购买资格
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.1 不为0 有购买资格 把下单信息保存到阻塞队列
//        long orderId = redisIdWorker.nextId("order:");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 2.2 订单id
//        voucherOrder.setId(orderId);
//        // 2.3 用户id
//        voucherOrder.setUserId(userId);
//        // 2.4 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        orderTasks.add(voucherOrder);
//        // 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        // 返回订单id
//        return Result.ok(orderId);
//    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单
        Long userId = voucherOrder.getUserId();
        // 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }
        // 5、扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }



    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        // 获取代理对象（事务）
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
}

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1、 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        // 2、 判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3、判断秒杀是否已经结束
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            // 尚未开始
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4、判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足！！！");
//        }
//        // 一人一单
//        Long userId = UserHolder.getUser().getId();
////        synchronized(userId.toString().intern()) {
//        // 自定义分布式锁
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 获取锁
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//           // 获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//        // 获取代理对象（事务）
////        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//    }

