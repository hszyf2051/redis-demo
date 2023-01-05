package com.hmdp.dto;

import lombok.Data;

/**
 * 登录请求参数
 * @author yif
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
}
