package com.pang.aidada.scoring;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pang.aidada.annotation.ScoringStrategyConfig;
import com.pang.aidada.common.ErrorCode;
import com.pang.aidada.exception.BusinessException;
import com.pang.aidada.model.dto.question.QuestionContentDTO;
import com.pang.aidada.model.entity.App;
import com.pang.aidada.model.entity.Question;
import com.pang.aidada.model.entity.ScoringResult;
import com.pang.aidada.model.entity.UserAnswer;
import com.pang.aidada.model.vo.QuestionVO;
import com.pang.aidada.service.QuestionService;
import com.pang.aidada.service.ScoringResultService;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;

/**
 * 自定义打分类应用评分策略
 */
@ScoringStrategyConfig(appType = 0, scoringStrategy = 0)
public class CustomScoreScoringStrategy implements ScoringStrategy {

    @Resource
    private QuestionService questionService;

    @Resource
    private ScoringResultService scoringResultService;

    @Override
    public UserAnswer doScore(List<String> choices, App app) throws Exception {
        // 判断答案是否为空
        if (choices == null || choices.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"选项不能为空");
        }

        Long appId = app.getId();
        // 1. 根据 id 查询到题目和题目结果信息（按分数降序排序）
        Question question = questionService.getOne(
                Wrappers.lambdaQuery(Question.class).eq(Question::getAppId, appId)
        );
        /*List<ScoringResult> scoringResultList = scoringResultService.list(
                Wrappers.lambdaQuery(ScoringResult.class)
                        .eq(ScoringResult::getAppId, appId)
                        .orderByDesc(ScoringResult::getResultScoreRange)
        );*/

        // 2. 统计用户的总得分
        int totalScore = 0;
        QuestionVO questionVO = QuestionVO.objToVo(question);
        List<QuestionContentDTO> questionContent = questionVO.getQuestionContent();

        // 判断答案数量是否与题目数量一致
        if(choices.size() != questionContent.size()){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"用户答案数量与题目数量不一致");
        }

        // 遍历题目列表
        for (int i = 0; i < questionContent.size(); i++){
            QuestionContentDTO questionContentDTO =  questionContent.get(i);
            //  获取对应题目用户选择的答案
            String answer = choices.get(i);
            // 遍历题目中的选项
            for (QuestionContentDTO.Option option : questionContentDTO.getOptions()) {
                // 如果答案和选项的key匹配
                if (option.getKey().equals(answer)) {
                    int score = Optional.of(option.getScore()).orElse(0);
                    totalScore += score;
                }
            }

        }

        // 3. 遍历得分结果，找到第一个用户分数大于得分范围的结果，作为最终结果
        /*ScoringResult maxScoringResult = scoringResultList.get(0);
        for (ScoringResult scoringResult : scoringResultList) {
            if (totalScore >= scoringResult.getResultScoreRange()) {
                maxScoringResult = scoringResult;
                break;
            }
        }*/
        // 第二种方式获取得分结果：直接通过SQL去评分结果表获取得分结果（resultScoreRange >= totalScore）
        // 但是要求评分结果集必须出现0分和满分的结果集，否则会存在查询结果为空或者结果不正确的情况
        ScoringResult maxScoringResult = scoringResultService.getOne(
                Wrappers.lambdaQuery(ScoringResult.class)
                        .eq(ScoringResult::getAppId, appId)
                        .ge(ScoringResult::getResultScoreRange, totalScore)
                        .orderByAsc(ScoringResult::getResultScoreRange)
                        .last("limit 1")
        );

        // 4. 构造返回值，填充答案对象的属性
        UserAnswer userAnswer = new UserAnswer();
        userAnswer.setAppId(appId);
        userAnswer.setAppType(app.getAppType());
        userAnswer.setScoringStrategy(app.getScoringStrategy());
        userAnswer.setChoices(JSONUtil.toJsonStr(choices));
        userAnswer.setResultId(maxScoringResult.getId());
        userAnswer.setResultName(maxScoringResult.getResultName());
        userAnswer.setResultDesc(maxScoringResult.getResultDesc());
        userAnswer.setResultPicture(maxScoringResult.getResultPicture());
        userAnswer.setResultScore(totalScore);
        return userAnswer;
    }
}