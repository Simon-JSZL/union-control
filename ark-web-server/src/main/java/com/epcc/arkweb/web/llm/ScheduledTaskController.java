package com.epcc.arkweb.web.llm;

import com.epcc.arkweb.helper.AuthContextHolder;
import com.epcc.arkweb.model.ShiroUser;
import com.union.control.service.ScheduledTaskService;
import com.epcc.arkweb.utils.ResultMsg;
import com.epcc.arkweb.vo.llm.ScheduledTaskCommandVO;
import com.epcc.arkweb.vo.llm.ScheduledTaskQueryVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

@Controller
@RequestMapping(value = {"llm", "union-op/llm"})
@RequiresPermissions(value = "/assistantManager/page")
public class ScheduledTaskController {

    @Autowired
    private ScheduledTaskService scheduledTaskService;

    @RequestMapping(value = "/scheduledTaskCreate", method = RequestMethod.POST)
    @ResponseBody
    public ResultMsg create(@RequestBody ScheduledTaskCommandVO command) {
        currentActor();
        return response(scheduledTaskService.create(command(command)));
    }

    @RequestMapping(value = "/scheduledTaskUpdate", method = RequestMethod.POST)
    @ResponseBody
    public ResultMsg update(@RequestBody ScheduledTaskCommandVO command) {
        currentActor();
        return response(scheduledTaskService.update(command(command)));
    }

    @RequestMapping(value = "/scheduledTaskList", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public ResultMsg list(ScheduledTaskQueryVO query) {
        currentActor();
        return response(scheduledTaskService.list(query.getKeyword(), query.getStatus(),
                page(query.getPage()), pageSize(query.getPageSize())));
    }

    @RequestMapping(value = "/scheduledTaskDetail", method = RequestMethod.GET)
    @ResponseBody
    public ResultMsg detail(@RequestParam Long taskId) {
        currentActor();
        return response(scheduledTaskService.detail(positive(taskId, "taskId")));
    }

    @RequestMapping(value = "/scheduledTaskRunList", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public ResultMsg runList(ScheduledTaskQueryVO query) {
        currentActor();
        query.setTaskId(positive(query.getTaskId(), "taskId"));
        return response(scheduledTaskService.runs(query.getTaskId(), page(query.getPage()),
                pageSize(query.getPageSize())));
    }

    @RequestMapping(value = "/scheduledTaskRunDetail", method = RequestMethod.GET)
    @ResponseBody
    public ResultMsg runDetail(@RequestParam Long runId) {
        currentActor();
        return response(scheduledTaskService.runDetail(positive(runId, "runId")));
    }

    @RequestMapping(value = "/scheduledTaskUnread", method = RequestMethod.GET)
    @ResponseBody
    public ResultMsg unread() {
        currentActor();
        return response(scheduledTaskService.unread());
    }

    @RequestMapping(value = "/scheduledTaskStart", method = RequestMethod.POST)
    @ResponseBody
    public ResultMsg start(@RequestBody IdCommand command) {
        currentActor();
        return response(scheduledTaskService.start(positive(command.getTaskId(), "taskId")));
    }

    @RequestMapping(value = "/scheduledTaskPause", method = RequestMethod.POST)
    @ResponseBody
    public ResultMsg pause(@RequestBody IdCommand command) {
        currentActor();
        return response(scheduledTaskService.pause(positive(command.getTaskId(), "taskId")));
    }

    @RequestMapping(value = "/scheduledTaskDiscard", method = RequestMethod.POST)
    @ResponseBody
    public ResultMsg discard(@RequestBody IdCommand command) {
        currentActor();
        return response(scheduledTaskService.discard(positive(command.getTaskId(), "taskId")));
    }

    @RequestMapping(value = "/scheduledTaskRunOpen", method = RequestMethod.POST)
    @ResponseBody
    public ResultMsg open(@RequestBody IdCommand command) {
        currentActor();
        return response(scheduledTaskService.open(positive(command.getRunId(), "runId")));
    }

    private static void currentActor() {
        ShiroUser user = AuthContextHolder.getAuthUserDetails();
        if (user == null || StringUtils.isBlank(user.getLoginName())
                || StringUtils.isBlank(user.getOrgCode())
                || StringUtils.isBlank(user.getRoleId())) {
            throw new org.apache.shiro.authz.UnauthenticatedException();
        }
    }

    private static Long positive(Long value, String name) {
        if (value == null || value <= 0L) {
            throw new IllegalArgumentException(name + " 非法");
        }
        return value;
    }

    private static int page(Integer value) { return value == null ? 1 : value; }
    private static int pageSize(Integer value) { return value == null ? 20 : value; }

    private static Map<String, Object> command(ScheduledTaskCommandVO value) {
        if (value == null) throw new IllegalArgumentException("请求参数非法");
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("taskId", value.getTaskId());
        command.put("title", value.getTitle());
        command.put("prompt", value.getPrompt());
        command.put("scheduleType", value.getScheduleType());
        command.put("runAt", value.getRunAt());
        command.put("cronExpression", value.getCronExpression());
        command.put("intervalSeconds", value.getIntervalSeconds());
        command.put("timezone", value.getTimezone());
        return command;
    }

    private static ResultMsg response(Map<String, Object> result) {
        if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
            return ResultMsg.fail(result == null ? "定时任务服务不可用"
                    : String.valueOf(result.get("errorMsg")));
        }
        return ResultMsg.ok(result.get("data"));
    }

    public static class IdCommand {
        private Long taskId;
        private Long runId;

        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public Long getRunId() { return runId; }
        public void setRunId(Long runId) { this.runId = runId; }
    }

}
