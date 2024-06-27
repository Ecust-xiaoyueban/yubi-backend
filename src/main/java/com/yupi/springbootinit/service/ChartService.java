package com.yupi.springbootinit.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yupi.springbootinit.model.dto.chart.ChartQueryRequest;
import com.yupi.springbootinit.model.dto.chart.GenChartByAiRequest;
import com.yupi.springbootinit.model.entity.Chart;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yupi.springbootinit.model.vo.BiResponse;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;

/**
 *
 */
public interface ChartService extends IService<Chart> {
    /**
     * 智能生成图表，同步
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    BiResponse genChartByAi(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request);

    /**
     * 智能生成图表，异步（线程池）
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    BiResponse genChartByAiAsync(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request);

    /**
     * 智能生成图表，异步（MQ）
     * @param multipartFile
     * @param genChartByAiRequest
     * @param request
     * @return
     */
    BiResponse genChartByAiMQ(MultipartFile multipartFile, GenChartByAiRequest genChartByAiRequest, HttpServletRequest request);

    /**
     * 分页查询我的图表
     * @param current
     * @param size
     * @param chartQueryRequest
     * @return
     */
    Page<Chart> getChartsByPage(long current, long size, ChartQueryRequest chartQueryRequest);
}
