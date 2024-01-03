package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.EmailUtil;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //发送并保存验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1、校验邮箱格式是否符合
        if (RegexUtils.isEmailInvalid(phone)){
            //2、不符合
            return Result.fail("邮箱格式输入错误！");
        }
        //3、生成6位数的验证码
        String code = RandomUtil.randomNumbers(6);
        //4、TODO 保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5、发送验证码
        EmailUtil.sendAuthCodeEmail(phone,code);
        log.info("【达人探店】您的验证码是："+code);
        //6、返回ok
        return Result.ok();
    }

    //实现登录功能
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();

        //1、校验邮箱格式是否正确
        if (RegexUtils.isEmailInvalid(loginForm.getPhone())){
            //邮箱格式不符合
            return Result.fail("邮箱格式输入错误！");
        }
        //2、校验验证码
        //TODO 从Redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (cacheCode == null || !code.equals(cacheCode)){
            //验证码校验错误
            return Result.fail("验证码错误！");
        }
        //3、根据邮箱查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotBlank(phone),User::getPhone,phone);
        User user = userMapper.selectOne(queryWrapper);
        //4、判断用户是否存在
        if (user == null){
            //用户不存在（创建新用户，并保存到数据库中）
            user = createNewUser(phone);
        }
        //5、TODO 保存用户信息到Redis中
        //5.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);//UUID类来自于hutool工具包
        //5.2 将user对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//BeanUtil类来自于hutool工具包
        //user转map时，由于id是Long类型，⽽StringRedisTemplate只⽀持String类型，因此需要⾃定义映射规则
        // 将对象中字段全部转成string类型，StringRedisTemplate只能存字符串类型的数据
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),CopyOptions.create().setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //5.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //5.4 设置token有效期  30分钟
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

        //6、结束返回token
        return Result.ok(token);
    }

    //创建新用户并保存到数据库中
    private User createNewUser(String phone) {
        //创建新用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //保存到数据库中
        userMapper.insert(user);
        return user;
    }
}
