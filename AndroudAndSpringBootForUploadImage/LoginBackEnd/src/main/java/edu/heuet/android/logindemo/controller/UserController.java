package edu.heuet.android.logindemo.controller;

import com.alibaba.druid.util.StringUtils;
import edu.heuet.android.logindemo.controller.viewobject.UserVO;
import edu.heuet.android.logindemo.error.BusinessException;
import edu.heuet.android.logindemo.error.EmBusinessError;
import edu.heuet.android.logindemo.response.CommonReturnType;
import edu.heuet.android.logindemo.response.OtpCode;
import edu.heuet.android.logindemo.service.UserService;
import edu.heuet.android.logindemo.service.model.UserModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import sun.misc.BASE64Encoder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/*
@Controller的作用在类级上，用来标记该类,让Spring可以扫描到
这一部分知识属于基础知识，可以参考《Spring实战（任意一版）》
*/
@Controller("user")
/* @RequestMapping是URL映射 */
@RequestMapping("/user")
/*
  @CrossOrigin解决跨域请求问题
  No 'Access-Control-Allow-Origin' header is present on the requested resource
  加上这个注解后，就会让response对象返回'Access-Control-Allow-Origin' header 为 通配符*
  但是单纯的加注解，只是让跨域互通了，还不能实现互信
  需要再加两个参数，才能实现前后端互信
  */
@CrossOrigin(allowCredentials = "true", allowedHeaders = "*")
/*
继承BaseController，
父类的方法子类不重新，就会引用父类的方法，
若子类重写父类的方法，则引用子类的方法
这里不需要进行重写handlerException
*/
public class UserController extends BaseController{
    private static Logger Log = LoggerFactory.getLogger(UserController.class);
    /* 带@Autowired的都是Bean，默认用单例模式创建 */
    @Autowired
    private UserService userService;

    @Autowired
    private HttpServletRequest httpServletRequest;

    private HttpSession session;

    /*  */
    /*
    **************** 对前两个注解的解释：****************
    1、这里的@RequestMapping作用在方法级，
        告诉我们不只有类级可以使用此注解，
        同时也提现了SpringMVC可以映射URL到具体方法的优秀设计
    2、这里需要指定@ResponseBody，
    具体理解可以参考[博客园](https://www.cnblogs.com/qiankun-site/p/5774325.html#4263851)
    这样返回的数据才会以JSON的格式写入到HttpServletResponse对象的body区
    浏览器才能呈现body区的JSON数据
    如果忘记指定@ResponseBody，则response对象为空，会报404错误
    （具体response对象是个啥？这是JavaEE基础Servlet里面的知识点，可以回头补习一下）
    另外，JSON格式的数据，推荐是否Firefox浏览器查看，会自动格式化

    **************** 对返回值类型CommonReturnType的解释：****************
    虽然我们在Service层，将dataobject整合成了UserModel
    但那是给Controller层诸如密码验证之类的，需要两张表的数据的业务准备的
    单纯的getUser()业务没必要返回UserModel，将用户敏感信息诸如加密密码，登录方式等等也透传给前端
    因此返回值应该再独立出一个viewobject层
    但是，仅仅返回viewobject对象的JSON序列化，这样设计没有通用性
    比如说，不知道返回值的状态到底是成功还是失败
    因此，应该再构造一个response层，对viewobject进行封装，返回CommonReturnType

    **************** 对@RequestParam注解的解释：****************
    @RequestParam用来获取URL中?后面的arg=value对应的参数值
    当然SpringMVC不只提供了这一种获取参数的方式，只不过这一注解最常用，其他注解还有好多
    具体可以参考[博客园](https://www.cnblogs.com/selinamee/p/5266266.html)
    */
    @RequestMapping("/get")
    @ResponseBody
    public CommonReturnType getUser(@RequestParam(name = "id") Integer id) throws BusinessException {
        /* 调用Service服务，获取id对应的用户信息UserModel
           将model转为viewobject
           再将viewobject进行封装成CommonReturnType
           返回CommonReturnType
        */
        UserModel userModel = userService.getUserById(id);
        /*
         若用户不存在，则抛出异常，抛到了Tomcat的容器层，
         如果把异常抛到Tomcat容器层后就不管了，那就只是会在控制台Tomcat日志中打印异常信息，
         无法返回到前端。
         好在SpringBoot有给出解决办法，就是再定义一个handlerException方法，
         写在了BaseController中。
         */
        if (userModel == null){
            /*
            如果把下面的代码注释掉，并且再引用一下userModel对象
             比如：
             userModel.setEncryptPassword("123")；
             因为userModel是null，引用空指针进行set，Java虚拟机会抛出java.lang.NullPointException
             这时候就会触发BaseController里面SpringBoot的注解@ExceptionHandler(Exception.class)
             然后判断到if (ex instanceof BusinessException)条件不成立
             继而返回UNKNOWN_ERROR的CommonReturnType
             从这里可以看出来UserController继承BaseController实现代码复用机制
             */
            throw new BusinessException(EmBusinessError.USER_NOT_EXIST);
        }
        UserVO userVO = convertFromModel(userModel);
        /*
           如果没有异常跑出，就表示返回的是正确的结果
           同时，因为有加@ResponseBody注解，该对象会被解析为JSON格式，呈现到前端
         */
        return CommonReturnType.create(userVO);
    }
    /* 将UserModel转为UserVO */
    private UserVO convertFromModel(UserModel userModel){
        if (userModel == null){ return  null; }
        UserVO userVO = new UserVO();
        /* 调用BeanUtils提供的复制属性方法，userVO不存在的属性会被自动丢弃 */
        BeanUtils.copyProperties(userModel,userVO);
        return userVO;
    }

    /*
    获取otp验证码
     三步走
     */
    @RequestMapping(value = "/getOtp", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType getOtp(@RequestParam(name = "telphone") String telphone) throws BusinessException {
        /* 0、用户获取验证码时，检测是否已存在注册用户 */
        boolean hasRegisted = userService.getUserByTelphone(telphone);
        if (hasRegisted){
//            BusinessException e =  new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"手机号已重复注册");
//            return CommonReturnType.create(e,"fail");
            // 这里throw，通过BaseController进行return
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR,"手机号已重复注册");
        }
        // 1、按照一定规则生成OTP验证码（6位）
        Random random = new Random();
        int randomInt = random.nextInt(99999); //[0,99999)
        randomInt += 100000; //[100000,199999)
        String otpCode = String.valueOf(randomInt);

        // 2、将OTP验证码与用户手机号进行绑定
        // 通过HttpServletRequest对象得到session，将手机号和验证码放到session中
        // 等注册流程需要的时候再去session获取
        session = httpServletRequest.getSession();
        session.setAttribute(telphone,otpCode);

        // 3、将OTP验证码通过短信通道发给用户，省略
        Log.info("telephone: " + telphone + "&otpCode: " + otpCode);

        // 4、将信息抽象为类
        OtpCode otpCodeObj = new OtpCode(telphone, otpCode);
        // 5、返回正确信息，方便前端获取
        return CommonReturnType.create(otpCodeObj, "successGetOtpCode");
    }

    /*
    用户注册接口
    接收参数统一使用字符串，接收后再进行类型转换
    */
    @RequestMapping(value = "/register", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType register(
            @RequestParam(name = "telphone") String telphone,
            @RequestParam(name = "otpCode") String otpCode,
            @RequestParam(name = "name") String name,
            @RequestParam(name = "age") String ageStr,
            @RequestParam(name = "gender") String genderStr,
            @RequestParam(name = "password") String password
                                        ) throws UnsupportedEncodingException, NoSuchAlgorithmException, BusinessException {
        // 从Session中获取对应手机号的验证码
        // otpCode是用户填写的，inSessionOtpCode是系统生成的
        String inSessionOtpCode = (String)session.getAttribute(telphone);
        Log.info("telphone: "+telphone+" inSessionOtpCode: "+ inSessionOtpCode +" otpCode: "+otpCode);
        if (!StringUtils.equals(otpCode, inSessionOtpCode)){
            Log.info("短信验证码错误");
//            BusinessException e = new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "短信验证码错误");
//            return CommonReturnType.create(e,"fail");
            // 这里throw，通过BaseController进行return
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR, "短信验证码错误");
        }
        // 类型转换，适配数据库
        int age = Integer.parseInt(ageStr);
        Byte gender = Byte.parseByte(genderStr);
        // 验证码通过后，进行注册流程
        UserModel userModel = new UserModel();
        userModel.setName(name);
        userModel.setGender(gender);
        userModel.setAge(age);
        userModel.setTelphone(telphone);
        userModel.setRegisterMode("byphone");
        userModel.setEncryptPassword(this.EncodeByMd5(password));

        userService.register(userModel);
        // 注册成功，只返回success即可
        return CommonReturnType.create(null);
    }

    /* 用户登录接口 */
    @RequestMapping(value = "/login", method = {RequestMethod.POST}, consumes = {CONTENT_TYPE_FORMED})
    @ResponseBody
    public CommonReturnType login(
            @RequestParam(value = "telphone",required = false) String telphone,
            @RequestParam(value = "password",required = false) String password
    ) throws BusinessException, UnsupportedEncodingException, NoSuchAlgorithmException {
        // 入参校验
        if (org.apache.commons.lang3.StringUtils.isEmpty(telphone)
            || org.apache.commons.lang3.StringUtils.isEmpty(password)
            ){
//            BusinessException e = new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
//            return CommonReturnType.create(e,"fail");
            // 这里throw，通过BaseController进行rerun
            throw new BusinessException(EmBusinessError.PARAMETER_VALIDATION_ERROR);
        }
        System.out.println("传进来参数了： telphone："+telphone
                +"password："+password);
        boolean hasRegisted = userService.getUserByTelphone(telphone);
        if(!hasRegisted){
            throw new BusinessException(EmBusinessError.USER_NOT_EXIST);
        }
        // 用户登录服务，校验登录是否合法
        UserModel userModel = userService.validateLogin(telphone, this.EncodeByMd5(password));

        // 将登陆凭证加入到用户登录成功的Session中
        // 切换web页面的时候，可以不用重复登录
        session.setAttribute("IS_LOGIN",true);
        session.setAttribute("LOGIN_USER", userModel);
        // 登录成功，只返回success即可
        return CommonReturnType.create(null);
    }

    // MD5加密+BASE64编码
    public String EncodeByMd5(String str) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        BASE64Encoder base64en = new BASE64Encoder();
        String newstr = base64en.encode(md5.digest(str.getBytes("utf-8")));
        return newstr;
    }
}
