package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
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
 * @author yif
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {


        // 1、校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2、不符合，返回错误消息
            return Result.fail("手机号格式错误");
        }
        // 3、符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.1、保存验证码到session
//        session.setAttribute("code",code);

        // 4.2、保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5、发送验证码
        // TODO 改成邮箱验证码校验
        log.debug("发送短信验证码成功，验证码是：{}" , code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 请求传来的手机号
        String phone = loginForm.getPhone();
        // 1、校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2、不符合，返回错误消息
            return Result.fail("手机号格式错误");
        }
        // 2、校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (StringUtils.isBlank(cacheCode) || !cacheCode.equals(code)) {
            // 3、不一致，报错
            return Result.fail("验证码错误！");
        }
        // 4、一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 5、判断用户是否存在
        if (ObjectUtils.isEmpty(user)) {
            // 6、若不存在则创建新用户
            user = createUserWithPhone(phone);
        }
        // 7、保存用户到Redis中
        // 7.1 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true );
        // 7.2 将User对象转化Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName, fieldValue) -> fieldValue.toString()));
        // 7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES );
        // 返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        log.debug("保存的user信息",user.toString());
        return user;
    }

}
