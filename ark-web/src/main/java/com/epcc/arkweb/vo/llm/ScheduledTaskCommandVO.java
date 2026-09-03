package com.epcc.arkweb.vo.llm;

/** Browser-editable task fields. Identity fields intentionally do not exist. */
public class ScheduledTaskCommandVO {
    private Long taskId;
    private String title;
    private String prompt;
    private String scheduleType;
    private String runAt;
    private String cronExpression;
    private Long intervalSeconds;
    private String timezone;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getScheduleType() { return scheduleType; }
    public void setScheduleType(String scheduleType) { this.scheduleType = scheduleType; }
    public String getRunAt() { return runAt; }
    public void setRunAt(String runAt) { this.runAt = runAt; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public Long getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(Long intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
}
