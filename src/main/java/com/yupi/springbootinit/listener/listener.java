package com.yupi.springbootinit.listener;

import com.yupi.springbootinit.constant.MQConstants;
import com.yupi.springbootinit.manager.AiManager;
import com.yupi.springbootinit.mapper.ChartMapper;
import com.yupi.springbootinit.model.entity.Chart;
import com.yupi.springbootinit.utils.ExcelUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class listener {

    private final ChartMapper chartMapper;

    private final AiManager aiManager;
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MQConstants.CHART_QUEUE_NAME),
            exchange = @Exchange(name = MQConstants.CHART_EXCHANGE_NAME),
            key = MQConstants.CHART_KEY
    ))
    public void listenGenChart(Chart chart){
        //先修改图表状态为执行中，等执行成功后，修改为"已完成"，保存执行结果;执行失败后，状态修改为"失败"，记录任务失败原因
        Chart updateChart = new Chart();
        updateChart.setId(chart.getId());
        updateChart.setStatus("running");
        chartMapper.updateById(updateChart);

        //用户输入
        StringBuilder userInput = new StringBuilder();
        String userGoal = chart.getGoal();
        if(StringUtils.isNotBlank(chart.getChartType())){
            userGoal  = ",请使用" + chart.getChartType();
        }
        userInput.append("分析需求: ").append(userGoal).append("\n");

        userInput.append(chart.getGoal()).append("\n");
        userInput.append("原始数据：").append("\n");
        //压缩后的数据
        userInput.append("数据： ").append(chart.getChartData()).append("\n");
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
    }
    public void handleAiGenError(long chartId, String execMessage){
        Chart AiGenResult = new Chart();
        AiGenResult.setId(chartId);
        AiGenResult.setStatus("failed");
        AiGenResult.setExecMessage(execMessage);
        chartMapper.updateById(AiGenResult);
    }
}
