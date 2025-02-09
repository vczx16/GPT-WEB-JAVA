package com.intelligent.bot.service.spark.impl;

import com.alibaba.fastjson.JSONObject;
import com.intelligent.bot.base.exception.E;
import com.intelligent.bot.constant.CommonConst;
import com.intelligent.bot.enums.sys.SendType;
import com.intelligent.bot.listener.spark.SparkDeskClient;
import com.intelligent.bot.model.MessageLog;
import com.intelligent.bot.model.SysConfig;
import com.intelligent.bot.model.req.spark.ChatReq;
import com.intelligent.bot.model.spark.*;
import com.intelligent.bot.service.baidu.BaiDuService;
import com.intelligent.bot.service.spark.ISparkService;
import com.intelligent.bot.service.sys.AsyncService;
import com.intelligent.bot.service.sys.CheckService;
import com.intelligent.bot.service.sys.IMessageLogService;
import com.intelligent.bot.utils.sys.JwtUtil;
import com.intelligent.bot.utils.sys.RedisUtil;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
@Transactional(rollbackFor = E.class)
@Log4j2
public class SparkServiceImpl implements ISparkService {

    @Resource
    CheckService checkService;

    @Resource
    BaiDuService baiDuService;

    @Resource
    IMessageLogService messageLogService;

    @Resource
    AsyncService asyncService;

    private static final String GENERAL_V2 = "generalv2";
    private static final String GENERAL_V3 = "generalv3";

    @Override
    public Long chat(ChatReq req) {
        SysConfig cacheObject = RedisUtil.getCacheObject(CommonConst.SYS_CONFIG);
        if(cacheObject.getIsOpenSpark() == 0){
            throw new E("星火大模型对话已关闭");
        }
        if(!baiDuService.textToExamine(req.getProblem())){
            throw new E("提问违反相关规定，请更换内容重新尝试");
        }
        List<Text> messages = messageLogService.createTextLogList(req.getLogId(),req.getProblem());
        Long logId = checkService.checkUser(MessageLog.builder()
                .useNumber(req.getType() == 2 ? CommonConst.SPARK_V2_NUMBER : CommonConst.SPARK_V3_NUMBER)
                .sendType(req.getType() == 2 ? SendType.SPARK_V2.getType() : SendType.SPARK_V3.getType())
                .useValue(JSONObject.toJSONString(messages))
                .userId(JwtUtil.getUserId()).build(),req.getLogId());
        SparkDeskClient sparkDeskClient = SparkDeskClient.builder()
                .host(req.getType() == 2 ? CommonConst.SPARK_API_HOST_WSS_V2_1 : CommonConst.SPARK_API_HOST_WSS_V3_1 )
                .appid(cacheObject.getSparkAppId())
                .apiKey(cacheObject.getSparkApiKey())
                .apiSecret(cacheObject.getSparkApiSecret())
                .build();
        InHeader header =
                InHeader.builder()
                        .uid(UUID.randomUUID().toString().substring(0, 10))
                        .appid(cacheObject.getSparkAppId())
                        .build();
        Random random = new Random();
        Parameter parameter =
                Parameter.builder()
                        .chat(Chat.builder()
                                .domain(req.getType() == 2 ? GENERAL_V2 : GENERAL_V3)
                                .maxTokens(2048)
                                .temperature(Math.round(random.nextDouble() * 10) / 10.0)
                                .build())
                        .build();
        InPayload payload =
                InPayload.builder()
                        .message(com.intelligent.bot.model.spark.Message.builder().text(messages).build())
                        .build();
        AIChatRequest aiChatRequest =
                AIChatRequest.builder()
                        .header(header)
                        .parameter(parameter)
                        .payload(payload)
                        .build();
        asyncService.sparkChat(sparkDeskClient,aiChatRequest,logId,JwtUtil.getUserId());
        return logId;
    }

}
