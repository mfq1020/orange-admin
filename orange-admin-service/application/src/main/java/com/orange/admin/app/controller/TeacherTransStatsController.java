package com.orange.admin.app.controller;

import com.github.pagehelper.PageHelper;
import com.orange.admin.app.model.*;
import com.orange.admin.app.service.*;
import com.orange.admin.common.core.object.*;
import com.orange.admin.common.core.util.*;
import com.orange.admin.common.core.constant.ErrorCodeEnum;
import com.orange.admin.common.core.annotation.MyRequestBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 老师流水统计操作控制器类。
 *
 * @author Stephen.Liu
 * @date 2020-04-11
 */
@Slf4j
@RestController
@RequestMapping("/admin/app/teacherTransStats")
public class TeacherTransStatsController {

    @Autowired
    private TeacherTransStatsService teacherTransStatsService;

    /**
     * 列出符合过滤条件的老师流水统计列表。
     *
     * @param teacherTransStatsFilter 过滤对象。
     * @param orderParam 排序参数。
     * @param pageParam 分页参数。
     * @return 应答结果对象，包含查询结果集。
     */
    @PostMapping("/list")
    public ResponseResult<?> list(
            @MyRequestBody TeacherTransStats teacherTransStatsFilter,
            @MyRequestBody MyOrderParam orderParam,
            @MyRequestBody MyPageParam pageParam) {
        if (pageParam != null) {
            PageHelper.startPage(pageParam.getPageNum(), pageParam.getPageSize());
        }
        String orderBy = MyOrderParam.buildOrderBy(orderParam, TeacherTransStats.class);
        List<TeacherTransStats> resultList = teacherTransStatsService.getTeacherTransStatsListWithRelation(teacherTransStatsFilter, orderBy);
        return ResponseResult.success(MyPageUtil.makeResponseData(resultList));
    }

    /**
     * 分组列出符合过滤条件的老师流水统计列表。
     *
     * @param teacherTransStatsFilter 过滤对象。
     * @param groupParam 分组参数。
     * @param orderParam 排序参数。
     * @param pageParam 分页参数。
     * @return 应答结果对象，包含查询结果集。
     */
    @PostMapping("/listWithGroup")
    public ResponseResult<?> listWithGroup(
            @MyRequestBody TeacherTransStats teacherTransStatsFilter,
            @MyRequestBody MyGroupParam groupParam,
            @MyRequestBody MyOrderParam orderParam,
            @MyRequestBody MyPageParam pageParam) {
        String orderBy = MyOrderParam.buildOrderBy(orderParam, TeacherTransStats.class);
        groupParam = MyGroupParam.buildGroupBy(groupParam, TeacherTransStats.class);
        if (groupParam == null) {
            return ResponseResult.error(
                    ErrorCodeEnum.INVALID_ARGUMENT_FORMAT, "数据参数错误，分组参数不能为空！");
        }
        if (pageParam != null) {
            PageHelper.startPage(pageParam.getPageNum(), pageParam.getPageSize());
        }
        MyGroupCriteria criteria = groupParam.getGroupCriteria();
        List<TeacherTransStats> resultList = teacherTransStatsService.getGroupedTeacherTransStatsListWithRelation(
                teacherTransStatsFilter, criteria.getGroupSelect(), criteria.getGroupBy(), orderBy);
        return ResponseResult.success(MyPageUtil.makeResponseData(resultList));
    }

    /**
     * 查看指定老师流水统计对象详情。
     *
     * @param statsId 指定对象主键Id。
     * @return 应答结果对象，包含对象详情。
     */
    @GetMapping("/view")
    public ResponseResult<TeacherTransStats> view(@RequestParam Long statsId) {
        ErrorCodeEnum errorCodeEnum = ErrorCodeEnum.NO_ERROR;
        String errorMessage = null;
        TeacherTransStats teacherTransStats = null;
        do {
            if (MyCommonUtil.existBlankArgument(statsId)) {
                errorCodeEnum = ErrorCodeEnum.ARGUMENT_NULL_EXIST;
                break;
            }
            teacherTransStats = teacherTransStatsService.getByIdWithRelation(statsId);
            if (teacherTransStats == null) {
                errorCodeEnum = ErrorCodeEnum.DATA_NOT_EXIST;
                break;
            }
        } while (false);
        return ResponseResult.create(errorCodeEnum, errorMessage, teacherTransStats);
    }
}
