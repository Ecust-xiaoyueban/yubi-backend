package com.yupi.springbootinit.service.impl;

import cn.hutool.core.io.FileUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.springbootinit.common.ErrorCode;
import com.yupi.springbootinit.constant.MQConstants;
import com.yupi.springbootinit.exception.BusinessException;
import com.yupi.springbootinit.exception.ThrowUtils;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.manager.RedisLimiterManager;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.model.entity.User;
import com.yupi.springbootinit.model.vo.BiResponse;
import com.yupi.springbootinit.service.ChartService;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.service.UserService;
import com.yupi.springbootinit.utils.ExcelUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 智能图表分析
 */
@Service
@RequiredArgsConstructor
public class ChartServiceImpl extends ServiceImpl<ChartMapper, Chart> implements ChartService{

    @Autowired
    private UserService userService;

    @Autowired
    private AiManager aiManager;

    @Resource
    private ChartMapper chartMapper;

    @Resource
    private RedisLimiterManager redisLimiterManager;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 智能生成图表，异步(MQ)
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @Override
    public BiResponse genChartByAiMQ(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        //参数检验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");

        //校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        //检验文件大小
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过1M");
        //检验文件后缀
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("xlsx", "xls");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix),ErrorCode.PARAMS_ERROR, "文件后缀非法");
        //获取用户id
        User loginUser = userService.getLoginUser(request);

        //限流判断
        redisLimiterManager.doRateLimit("genChartByAi" + String.valueOf(loginUser.getId()));

        //用户输入
        StringBuilder userInput = new StringBuilder();
        String userGoal = goal;
        if(StringUtils.isNotBlank(chartType)){
            userGoal  = ",请使用" + chartType;
        }
        userInput.append("分析需求: ").append(userGoal).append("\n");

        userInput.append(goal).append("\n");
        userInput.append("原始数据：").append("\n");
        //压缩后的数据
        String csvData = ExcelUtils.excelToCsv(multipartFile);
        userInput.append("数据： ").append(csvData).append("\n");

        //插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(csvData);
        chart.setChartType(chartType);
        chart.setStatus("wait");
        chart.setUserId(loginUser.getId());
        chartMapper.insert(chart);

        rabbitTemplate.convertAndSend(MQConstants.CHART_EXCHANGE_NAME, MQConstants.CHART_KEY, chart);
        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(chart.getId());
        return  biResponse;
    }

    /**
     * 智能生成图表，异步(线程池)
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @Override
    public BiResponse genChartByAiAsync(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        //参数检验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");

        //校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        //检验文件大小
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过1M");
        //检验文件后缀
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("xlsx", "xls");
        ThrowUtils.throwIf(!validFileSuffixList.contains(suffix),ErrorCode.PARAMS_ERROR, "文件后缀非法");
        //获取用户id
        User loginUser = userService.getLoginUser(request);

        //限流判断
        redisLimiterManager.doRateLimit("genChartByAi" + String.valueOf(loginUser.getId()));
        //关键词prompt
//        final String prompt = "你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：\n" +
//                "分析需求：\n" +
//                "{数据分析的需求或者目标}\n" +
//                "原始数据：\n" +
//                "{csv格式的原始数据，用,作为分割}\n" +
//                "请根据这两部分内容，按照以下指定格式生成内容（此外不要输出任何多余的开头、结尾、注释）\n" +
//                "【【【【【\n" +
//                "{前端Echarts V5的option配置对象js代码，合理地将数据进行可视化，不要生成任何多余的内容，比如注释}\n" +
//                "【【【【【\n" +
//                "{明确的数据分析结论、越详细越好，不要生成多余的注释}\n" +
//                "【【【【【";
        //用户输入
        StringBuilder userInput = new StringBuilder();
        String userGoal = goal;
        if(StringUtils.isNotBlank(chartType)){
            userGoal  = ",请使用" + chartType;
        }
        userInput.append("分析需求: ").append(userGoal).append("\n");

        userInput.append(goal).append("\n");
        userInput.append("原始数据：").append("\n");
        //压缩后的数据
        String csvData = ExcelUtils.excelToCsv(multipartFile);
        userInput.append("数据： ").append(csvData).append("\n");

        //插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(csvData);
        chart.setChartType(chartType);
        chart.setStatus("wait");
        chart.setUserId(loginUser.getId());
        int saveResult = chartMapper.insert(chart);


        CompletableFuture.runAsync(() -> {
            //先修改图表状态为执行中，等执行成功后，修改为"已完成"，保存执行结果;执行失败后，状态修改为"失败"，记录任务失败原因
            Chart updateChart = new Chart();
            updateChart.setId(chart.getId());
            updateChart.setStatus("running");
            chartMapper.updateById(updateChart);
            //将输入传给AI
            String answer = aiManager.doChat(1802676776325820418L, userInput.toString());
            //将结果拆分
            String[] splits = answer.split("【【【【【");
            if(splits.length < 3){
                //throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI生成错误");
                handleAiGenError(chart.getId(), "AI生成错误");
                return;
            }
            String genChart = splits[1].trim();
            String genResult = splits[2].trim();
            Chart updateChart2 = new Chart();
            updateChart2.setGenChart(genChart);
            updateChart2.setGenResult(genResult);
            updateChart2.setId(chart.getId());
            updateChart2.setStatus("succeed");
            chartMapper.updateById(updateChart2);
        }, threadPoolExecutor);

        BiResponse biResponse = new BiResponse();
        biResponse.setChartId(chart.getId());
        return  biResponse;
    }

    /**
     * 智能生成图表，同步
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    @Override
    public BiResponse genChartByAi(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request) {
        String name = genChartByAiRequest.getName();
        String goal = genChartByAiRequest.getGoal();
        String chartType = genChartByAiRequest.getChartType();
        //参数检验
        ThrowUtils.throwIf(StringUtils.isBlank(goal), ErrorCode.PARAMS_ERROR, "目标为空");
        ThrowUtils.throwIf(StringUtils.isNotBlank(name) && name.length() > 100, ErrorCode.PARAMS_ERROR, "名称过长");

        //校验文件
        long size = multipartFile.getSize();
        String originalFilename = multipartFile.getOriginalFilename();
        //检验文件大小
        final long ONE_MB = 1024 * 1024L;
        ThrowUtils.throwIf(size > ONE_MB, ErrorCode.PARAMS_ERROR, "文件超过1M");
        //检验文件后缀
        String suffix = FileUtil.getSuffix(originalFilename);
        final List<String> validFileSuffixList = Arrays.asList("png", "jpg", "svg", "webp", "jpeg");
        ThrowUtils.throwIf(validFileSuffixList.contains(suffix),ErrorCode.PARAMS_ERROR, "文件后缀非法");
        //获取用户id
        User loginUser = userService.getLoginUser(request);

        //限流判断
        redisLimiterManager.doRateLimit("genChartByAi" + String.valueOf(loginUser.getId()));
        //关键词prompt
//        final String prompt = "你是一个数据分析师和前端开发专家，接下来我会按照以下固定格式给你提供内容：\n" +
//                "分析需求：\n" +
//                "{数据分析的需求或者目标}\n" +
//                "原始数据：\n" +
//                "{csv格式的原始数据，用,作为分割}\n" +
//                "请根据这两部分内容，按照以下指定格式生成内容（此外不要输出任何多余的开头、结尾、注释）\n" +
//                "【【【【【\n" +
//                "{前端Echarts V5的option配置对象js代码，合理地将数据进行可视化，不要生成任何多余的内容，比如注释}\n" +
//                "【【【【【\n" +
//                "{明确的数据分析结论、越详细越好，不要生成多余的注释}\n" +
//                "【【【【【";
        //用户输入
        StringBuilder userInput = new StringBuilder();
        String userGoal = goal;
        if(StringUtils.isNotBlank(chartType)){
            userGoal  = ",请使用" + chartType;
        }
        userInput.append("分析需求: ").append(userGoal).append("\n");

        userInput.append(goal).append("\n");
        userInput.append("原始数据：").append("\n");
        //压缩后的数据
        String csvData = ExcelUtils.excelToCsv(multipartFile);
        userInput.append("数据： ").append(csvData).append("\n");

        //将输入传给AI
        String answer = aiManager.doChat(1802676776325820418L, userInput.toString());
        System.out.println("this is answer: " + answer);
        //将结果拆分
        String[] splits = answer.split("【【【【【");
        if(splits.length < 3){
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI生成错误");
        }
        String genChart = splits[1].trim();
        String genResult = splits[2].trim();

        //插入到数据库
        Chart chart = new Chart();
        chart.setName(name);
        chart.setGoal(goal);
        chart.setChartData(csvData);
        chart.setChartType(chartType);
        chart.setGenChart(genChart);
        chart.setGenResult(genResult);
        chart.setUserId(loginUser.getId());
        chart.setStatus("succeed");
        int saveResult = chartMapper.insert(chart);
        BiResponse biResponse = new BiResponse();
        biResponse.setGenChart(genChart);
        biResponse.setGenResult(genResult);
        biResponse.setChartId(chart.getId());

        return  biResponse;
    }
    public void handleAiGenError(long chartId, String execMessage){
        Chart AiGenResult = new Chart();
        AiGenResult.setId(chartId);
        AiGenResult.setStatus("failed");
        AiGenResult.setExecMessage(execMessage);
        chartMapper.updateById(AiGenResult);
    }

    @Override
    public Page<Chart> getChartsByPage(long current, long size, ChartQueryRequest chartQueryRequest) {
        Page<Chart> page = new Page<>(current, size);
        QueryWrapper<Chart> queryWrapper = getQueryWrapper(chartQueryRequest);
        return chartMapper.selectPage(page, queryWrapper);
    }

    public QueryWrapper<Chart> getQueryWrapper(ChartQueryRequest chartQueryRequest) {
        QueryWrapper<Chart> queryWrapper = new QueryWrapper<>();

        // 添加其他查询条件
        if (chartQueryRequest.getId() != null) {
            queryWrapper.eq("id", chartQueryRequest.getId());
        }
        if (chartQueryRequest.getGoal() != null) {
            queryWrapper.eq("goal", chartQueryRequest.getGoal());
        }
        if (chartQueryRequest.getGoal() != null) {
            queryWrapper.eq("goal", chartQueryRequest.getGoal());
        }
        if (chartQueryRequest.getName() != null) {
            queryWrapper.eq("name", chartQueryRequest.getName());
        }
        if (chartQueryRequest.getChartType() != null) {
            queryWrapper.eq("charType", chartQueryRequest.getChartType());
        }
        if (chartQueryRequest.getUserId() != null) {
            queryWrapper.eq("userId", chartQueryRequest.getUserId());
        }


        // 添加时间排序
        queryWrapper.orderByDesc("createTime");

        return queryWrapper;
    }
}




