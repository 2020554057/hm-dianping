package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询秒杀券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2、判断秒杀是否开始
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //未开始
            return Result.fail("秒杀未开始！");
        }
        //3、判断秒杀是否结束
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            //结束了
            return Result.fail("秒杀已结束！");
        }
        //4、判断是否有库存
        if (seckillVoucher.getStock() < 1) {
            //库存未空
            return Result.fail("优惠券已经抢完了！");
        }

        // 5、创建订单
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {//给用户加锁，id
            // 创建代理对象，使用代理对象调用第三方事务方法， 防止事务失效
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单逻辑
        // 5.1.用户id
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        //6、扣减库存
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock",0)//TODO ：这里使用到了乐观锁解决并发超卖的问题
                .update();
        if (!flag) {
            return Result.fail("秒杀券扣减失败!");
        }
        //7、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1、订单id
        long orderID = redisIdWorker.nextId("order");
        voucherOrder.setId(orderID);
        //7.2、用户id
        Long userID = UserHolder.getUser().getId();
        voucherOrder.setUserId(userID);
        //7.3、优惠券id
        voucherOrder.setVoucherId(voucherId);
        //8、向数据库中添加数据
        boolean isCreate = save(voucherOrder);
        if (!isCreate) {
            return Result.fail("创建秒杀券订单失败！");
        }

        //9、返回订单id
        return Result.ok(voucherOrder.getId());
    }
}
