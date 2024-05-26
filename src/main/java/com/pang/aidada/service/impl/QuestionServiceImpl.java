package com.pang.aidada.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.pang.aidada.common.ErrorCode;
import com.pang.aidada.constant.CommonConstant;
import com.pang.aidada.exception.BusinessException;
import com.pang.aidada.exception.ThrowUtils;
import com.pang.aidada.manager.AiManager;
import com.pang.aidada.mapper.QuestionMapper;
import com.pang.aidada.model.dto.question.QuestionContentDTO;
import com.pang.aidada.model.dto.question.QuestionQueryRequest;
import com.pang.aidada.model.entity.App;
import com.pang.aidada.model.entity.Question;
import com.pang.aidada.model.entity.User;
import com.pang.aidada.model.enums.AppTypeEnum;
import com.pang.aidada.model.vo.QuestionVO;
import com.pang.aidada.model.vo.UserVO;
import com.pang.aidada.service.AppService;
import com.pang.aidada.service.QuestionService;
import com.pang.aidada.service.UserService;
import com.pang.aidada.utils.SqlUtils;
import com.zhipu.oapi.service.v4.model.ModelData;
import io.reactivex.Flowable;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 题目服务实现
 *

 */
@Service
@Slf4j
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {

    @Resource
    private UserService userService;

    @Resource
    private AppService appService;

    @Resource
    private AiManager aiManager;

    /**
     * 校验数据
     *
     * @param question
     * @param add      对创建的数据进行校验
     */
    @Override
    public void validQuestion(Question question, boolean add) {
        ThrowUtils.throwIf(question == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        String questionContent = question.getQuestionContent();
        Long appId = question.getAppId();
        // 创建数据时，参数不能为空
        if (add) {
            // 补充校验规则
            ThrowUtils.throwIf(StringUtils.isBlank(questionContent), ErrorCode.PARAMS_ERROR, "题目内容不能为空");
            ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "appId 非法");
        }
        // 修改数据时，有参数则校验
        // 补充校验规则
        if (appId != null) {
            App app = appService.getById(appId);
            ThrowUtils.throwIf(app == null, ErrorCode.PARAMS_ERROR, "应用不存在");
        }
    }

    /**
     * 获取查询条件
     *
     * @param questionQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<Question> getQueryWrapper(QuestionQueryRequest questionQueryRequest) {
        QueryWrapper<Question> queryWrapper = new QueryWrapper<>();
        if (questionQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = questionQueryRequest.getId();
        String questionContent = questionQueryRequest.getQuestionContent();
        Long appId = questionQueryRequest.getAppId();
        Long userId = questionQueryRequest.getUserId();
        Long notId = questionQueryRequest.getNotId();
        String sortField = questionQueryRequest.getSortField();
        String sortOrder = questionQueryRequest.getSortOrder();

        // 补充需要的查询条件
        // 模糊查询
        queryWrapper.like(StringUtils.isNotBlank(questionContent), "questionContent", questionContent);
        // 精确查询
        queryWrapper.ne(ObjectUtils.isNotEmpty(notId), "id", notId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjectUtils.isNotEmpty(appId), "appId", appId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        // 排序规则
        queryWrapper.orderBy(SqlUtils.validSortField(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * 获取题目封装
     *
     * @param question
     * @param request
     * @return
     */
    @Override
    public QuestionVO getQuestionVO(Question question, HttpServletRequest request) {
        // 对象转封装类
        QuestionVO questionVO = QuestionVO.objToVo(question);

        // 可以根据需要为封装对象补充值，不需要的内容可以删除
        // region 可选
        // 1. 关联查询用户信息
        Long userId = question.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userService.getById(userId);
        }
        UserVO userVO = userService.getUserVO(user);
        questionVO.setUser(userVO);
        // endregion

        return questionVO;
    }

    /**
     * 分页获取题目封装
     *
     * @param questionPage
     * @param request
     * @return
     */
    @Override
    public Page<QuestionVO> getQuestionVOPage(Page<Question> questionPage, HttpServletRequest request) {
        List<Question> questionList = questionPage.getRecords();
        Page<QuestionVO> questionVOPage = new Page<>(questionPage.getCurrent(), questionPage.getSize(), questionPage.getTotal());
        if (CollUtil.isEmpty(questionList)) {
            return questionVOPage;
        }
        // 对象列表 => 封装对象列表
        List<QuestionVO> questionVOList = questionList.stream().map(question -> {
            return QuestionVO.objToVo(question);
        }).collect(Collectors.toList());

        // 可以根据需要为封装对象补充值，不需要的内容可以删除
        // region 可选
        // 1. 关联查询用户信息
        Set<Long> userIdSet = questionList.stream().map(Question::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 填充信息
        questionVOList.forEach(questionVO -> {
            Long userId = questionVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            questionVO.setUser(userService.getUserVO(user));
        });
        // endregion

        questionVOPage.setRecords(questionVOList);
        return questionVOPage;
    }

    /**
     * AI 生成题目的系统提示语（固定）
     */
    private static final String GENERATE_QUESTION_SYSTEM_MESSAGE = "你是一位严谨的出题专家，我会给你如下信息：\n" +
            "```\n" +
            "应用名称，\n" +
            "【【【应用描述】】】，\n" +
            "应用类别，\n" +
            "要生成的题目数，\n" +
            "每个题目的选项数\n" +
            "```\n" +
            "\n" +
            "请你根据上述信息，按照以下步骤来出题：\n" +
            "1. 要求：题目和选项尽可能地短，题目不要包含序号，每题的选项数以我提供的为主，题目不能重复\n" +
            "2. 严格按照下面的 json 格式输出题目和选项\n" +
            "```\n" +
            "[{\"options\":[{\"value\":\"选项内容\",\"key\":\"A\"},{\"value\":\"\",\"key\":\"B\"}],\"title\":\"题目标题\"}]\n" +
            "```\n" +
            "title 是题目，options 是选项，每个选项的 key 按照英文字母序（比如 A、B、C、D）以此类推，value 是选项内容\n" +
            "3. 检查题目是否包含序号，若包含序号则去除序号\n" +
            "4. 返回的题目列表格式必须为 JSON 数组";

    /**
     * AI生成题目
     *
     * @param app
     * @param questionNumber
     * @param optionNumber
     * @return
     */
    @Override
    public List<QuestionContentDTO> aiGenerateQuestion(App app, int questionNumber, int optionNumber) {
        // 封装 Prompt
        String userMessage = getGenerateQuestionUserMessage(app, questionNumber, optionNumber);
        // AI 生成
        String result = aiManager.doSyncStableRequest(GENERATE_QUESTION_SYSTEM_MESSAGE, userMessage);
        // 返回结果类似于
        /*
        ```json[
        {"options":[{"value":"心理学","key":"A"},{"value":"物理学","key":"B"}],"title":"智商测试属于哪一类【应用类别】？"},
        {"options":[{"value":"100以上","key":"A"},{"value":"200以下","key":"B"}],"title":"大多数人的智商分数在哪个范围内？"}
        ]...
        */
        // 截取需要的 JSON 信息
        int start = result.indexOf("[");
        int end = result.lastIndexOf("]");
        String json = result.substring(start, end + 1);
        List<QuestionContentDTO> questionContentDTOList = JSONUtil.toList(json, QuestionContentDTO.class);
        return questionContentDTOList;
    }

    @Override
    public SseEmitter aiGenerateQuestionSSE(App app, int questionNumber, int optionNumber) {
        // 调用AI接口前建立SSE连接对象，0 表示不超时
        SseEmitter emitter = new SseEmitter(0L);

        // AI 生成，sse 流式返回
        // 封装 Prompt
        String userMessage = getGenerateQuestionUserMessage(app, questionNumber, optionNumber);
        Flowable<ModelData> modelDataFlowable = aiManager.doStreamRequest(GENERATE_QUESTION_SYSTEM_MESSAGE, userMessage, null);

        // 左括号计数器，除了默认值外，当回归为 0 时，表示左括号等于右括号，可以截取
        AtomicInteger counter = new AtomicInteger(0);
        // 拼接完整题目
        StringBuilder contentBuilder = new StringBuilder();

        modelDataFlowable
                // 异步线程池执行
                .observeOn(Schedulers.io())
                // 获取流返回的数据
                .map(modelData -> modelData.getChoices().get(0).getDelta().getContent())
                // 将所有的空格、换行符替换为空字符串
                .map(message -> message.replace("\\s",""))
                // 过滤掉空字符串
                .filter(StrUtil::isNotBlank)
                // 将字符串转换为 List<Character>
                .flatMap(message -> {
                    List<Character> characters = new ArrayList<>();
                    for(char c : message.toCharArray()){
                        characters.add(c);
                    }
                    return  Flowable.fromIterable(characters);
                })
                // 获取流数据后开始进行处理
                .doOnNext(c -> {
                    // 识别第一个 [ 表示开始 AI 传输 json 数据，打开 flag 开始拼接 json 数组
                    if(c == '{'){
                        counter.addAndGet(1);
                    }
                    // 获取有效的流数据
                    if(counter.get() > 0 ){
                        contentBuilder.append(c);
                    }
                    if(c == '}'){
                        counter.addAndGet(-1);
                        if(counter.get() == 0){
                            // 累积单套题目满足 json 格式后，sse 推送至前端
                            // sse 需要压缩成当行 json，sse 无法识别换行
                            emitter.send(JSONUtil.toJsonStr(contentBuilder.toString()));
                            // 重置 contentBuilder
                            contentBuilder.setLength(0);
                        }
                    }
                })
                .doOnError((e) -> {
                    log.error("SSE error {}",e);
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR);
                })
                .doOnComplete(emitter::complete)
                .subscribe();

        return emitter;
    }

    /**
     * 生成题目的用户消息
     *  类似于
     *  小学数学测验，
     * 【【【小学三年级的数学题】】】，
     * 得分类，
     * 10，
     * 3
     *
     * @param app
     * @param questionNumber
     * @param optionNumber
     * @return
     */
    private String getGenerateQuestionUserMessage(App app, int questionNumber, int optionNumber) {
        StringBuilder userMessage = new StringBuilder();
        userMessage.append(app.getAppName()).append("\n");
        userMessage.append(app.getAppDesc()).append("\n");
        userMessage.append(AppTypeEnum.getEnumByValue(app.getAppType()).getText() + "类").append("\n");
        userMessage.append(questionNumber).append("\n");
        userMessage.append(optionNumber);
        return userMessage.toString();
    }

}