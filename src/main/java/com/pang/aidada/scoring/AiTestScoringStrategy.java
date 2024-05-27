package com.pang.aidada.scoring;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pang.aidada.annotation.ScoringStrategyConfig;
import com.pang.aidada.common.ErrorCode;
import com.pang.aidada.exception.BusinessException;
import com.pang.aidada.manager.AiManager;
import com.pang.aidada.model.dto.question.QuestionAnswerDTO;
import com.pang.aidada.model.dto.question.QuestionContentDTO;
import com.pang.aidada.model.entity.App;
import com.pang.aidada.model.entity.Question;
import com.pang.aidada.model.entity.ScoringResult;
import com.pang.aidada.model.entity.UserAnswer;
import com.pang.aidada.model.vo.QuestionVO;
import com.pang.aidada.service.QuestionService;
import com.pang.aidada.service.ScoringResultService;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 测评类应用评分策略
 */
@ScoringStrategyConfig(appType = 1, scoringStrategy = 1)
public class AiTestScoringStrategy implements ScoringStrategy {

    @Resource
    private QuestionService questionService;

    @Resource
    private AiManager aiManager;

    /**
     * 引入本地缓存，用于缓存AI评分结果
     */
    private final Cache<String, String> answerCacheMap =
            Caffeine.newBuilder().initialCapacity(1024)
                    // 缓存5分钟移除
                    .expireAfterAccess(5L, TimeUnit.MINUTES)
                    .build();

    /**
     * AI 评分系统提示语（固定）
     */
    private static final String AI_TEST_SCORING_SYSTEM_MESSAGE = "你是一位严谨的判题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称，\n" +
            "【【【应用描述】】】，\n" +
            "题目和用户回答的列表：格式为 [{\"title\": \"题目\",\"answer\": \"用户回答\"}]\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤来对用户进行评价：\n" +
            "1. 要求：需要给出一个明确的评价结果，包括评价名称（尽量简短）和评价描述（尽量详细，大于 200 字）\n" +
            "2. 严格按照下面的 json 格式输出评价名称和评价描述\n" +
            "```\n" +
            "{\"resultName\": \"评价名称\", \"resultDesc\": \"评价描述\"}\n" +
            "```\n" +
            "3. 返回格式必须为 JSON 对象";

    @Override
    public UserAnswer doScore(List<String> choices, App app) throws Exception {
        // 判断答案是否为空
        if (choices == null || choices.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"选项不能为空");
        }

        Long appId = app.getId();
        String choicesStr = JSONUtil.toJsonStr(choices);
        String cacheKey = buildCacheKey(appId, choicesStr);
        String answerJson = answerCacheMap.getIfPresent(cacheKey);
        if(StrUtil.isNotBlank(answerJson)){
            // 3. 构造返回值，填充答案对象的属性
            UserAnswer userAnswer = JSONUtil.toBean(answerJson, UserAnswer.class);
            userAnswer.setAppId(appId);
            userAnswer.setAppType(app.getAppType());
            userAnswer.setScoringStrategy(app.getScoringStrategy());
            userAnswer.setChoices(choicesStr);
            return userAnswer;
        }

        // 1. 根据 id 查询到题目和题目结果信息
        Question question = questionService.getOne(
                Wrappers.lambdaQuery(Question.class).eq(Question::getAppId, appId)
        );

        QuestionVO questionVO = QuestionVO.objToVo(question);
        List<QuestionContentDTO> questionContent = questionVO.getQuestionContent();

        // 判断答案数量是否与题目数量一致
        if(choices.size() != questionContent.size()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户答案数量与题目数量不一致");
        }

        // 2.调用AI获取AI评分结果
        // 封装用户消息
        String userMessage = getAiTestScoringUserMessage(app, questionContent, choices);
        // 调用AI
        String result = aiManager.doSyncStableRequest(AI_TEST_SCORING_SYSTEM_MESSAGE, userMessage);
        // 返回结果类似于
        /*
        ...{
            "resultName": "INTJ",
                "resultDesc": "INTJ被称为'策略家'或'建筑师'，是一个高度独立和具有战略思考能力的性格类型"
        }...
        */
        // 截取需要的 JSON 信息
        int start = result.indexOf("{");
        int end = result.lastIndexOf("}");
        String json = result.substring(start, end + 1);

        // 将答案写入本地缓存
        answerCacheMap.put(cacheKey,json);

        // 3. 构造返回值，填充答案对象的属性
        UserAnswer userAnswer = JSONUtil.toBean(json, UserAnswer.class);
        userAnswer.setAppId(appId);
        userAnswer.setAppType(app.getAppType());
        userAnswer.setScoringStrategy(app.getScoringStrategy());
        userAnswer.setChoices(choicesStr);
        return userAnswer;
    }

    /**
     * AI 评分用户消息封装
     * 类似于
     * MBTI 性格测试，
     * 【【【快来测测你的 MBTI 性格】】】，
     * [{"title": "你通常更喜欢","answer": "独自工作"}, {"title": "当安排活动时","answer": "更愿意随机应变"}]
     *
     * @param app
     * @param questionContentDTOList
     * @param choices
     * @return
     */
    private String getAiTestScoringUserMessage(App app, List<QuestionContentDTO> questionContentDTOList, List<String> choices) {
        // 将题目名称、题目描述以及选项内容、用户答案拼接起来
        StringBuilder userMessage = new StringBuilder();
        userMessage.append(app.getAppName()).append("\n");
        userMessage.append(app.getAppDesc()).append("\n");
        List<QuestionAnswerDTO> questionAnswerDTOList = new ArrayList<>();
        for (int i = 0; i < questionContentDTOList.size(); i++) {
            QuestionAnswerDTO questionAnswerDTO = new QuestionAnswerDTO();
            questionAnswerDTO.setTitle(questionContentDTOList.get(i).getTitle());
            questionAnswerDTO.setUserAnswer(choices.get(i));
            questionAnswerDTOList.add(questionAnswerDTO);
        }
        userMessage.append(JSONUtil.toJsonStr(questionAnswerDTOList));
        return userMessage.toString();
    }

    /**
     * 生成缓存key
     * @param appId
     * @param choices
     * @return
     */
    private String buildCacheKey(Long appId,String choices){
        return DigestUtil.md5Hex(appId + ":" + choices);
    }
}