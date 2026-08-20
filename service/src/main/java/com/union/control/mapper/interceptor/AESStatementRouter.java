package com.union.control.mapper.interceptor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.nucc.channel.ark.common.util.Constant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AESStatementRouter {
    private static final Set<String> ADDRESS_BOOK_STATEMENTS = immutable(
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.getAddressBookPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.queryById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.selectAllUsers",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.selectByOrgCodeList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.selectByOrgCodeAndParaList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.getUserEmail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceUserGroupRelationMapper.getAddressMailOfBusinessType",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceUserGroupRelationMapper.selectNewUserByGroupId",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookMapper.queryByUserAccount",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceUserGroupRelationMapper.selectUserByGroupId");

    private static final Set<String> DECRYPT_STATEMENTS = immutable(
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookRecordMapper.selectPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.queryReformTrackItemsById",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.queryReformTrackItemsByConditions",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.queryQuestionnaireListByUpdateOrgCodeAndNo",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.queryQuestionnaireListByParam",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.selectAnnounceMailPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryAnnounceMailSend",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.getByBatchId",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.getBy",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.getById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryBySeriesNo",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryByIds",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.queryBySeriesNoS",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.selectJiraNoticeResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.selectMailList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.selectAnnounceMailPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.queryAnnounceMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.listBy",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.getNormalMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.checkMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.getAnnounceMailById",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.getAutoAuditPass",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.queryBySeriesNos",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.queryNoticeExemptionMail",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.selectAppealMailPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.selectRelatedInfo",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.selectAppealPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.selectByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.selectProcess",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.getTicketById",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.queryTicketListByParamsPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.exportTicketListByParams",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.queryTicketByOrgCode",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.selectByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.selectByRecord",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.selectPageResult",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.queryLastNotify",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.queryLastDashBoardNotify");

    private static final Set<String> ENCRYPT_STATEMENTS = immutable(
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceAddressBookRecordMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.batchInsertReformTrackItems",
            "com.nucc.channel.ark.dao.mapper.announce.ReformTrackMapper.batchUpdateReformTrackItems",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.updateQuestionnaire",
            "com.nucc.channel.ark.dao.mapper.announce.QuestionnaireMapper.insertBatchQuestionnaireList",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailSendMapper.insertBatch",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.insertBatch",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.batchUpdateMailAudit",
            "com.nucc.channel.ark.dao.mapper.announce.AnnounceMailMapper.update",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.insertSelective",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.updateByPrimaryKeySelective",
            "com.nucc.channel.ark.dao.mapper.announce.AppealMapper.updateByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.TicketMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.insert",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.insertSelective",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.updateByPrimaryKeySelective",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.updateByPrimaryKeyWithBLOBs",
            "com.nucc.channel.ark.dao.mapper.announce.MailDetailMapper.updateByPrimaryKey",
            "com.nucc.channel.ark.dao.mapper.announce.NotifyMapper.insert");

    StatementRoute resolve(String statementId) {
        StatementRoute route = routeOf(statementId);
        if (route == StatementRoute.PASSTHROUGH || !dynamicFlagAllows(statementId, route)) {
            return StatementRoute.PASSTHROUGH;
        }
        return route;
    }

    static boolean isSensitiveQuery(String statementId) {
        StatementRoute route = routeOf(statementId);
        return route == StatementRoute.DECRYPT || route == StatementRoute.ADDRESS_BOOK;
    }

    private static StatementRoute routeOf(String statementId) {
        if (ADDRESS_BOOK_STATEMENTS.contains(statementId)) return StatementRoute.ADDRESS_BOOK;
        if (DECRYPT_STATEMENTS.contains(statementId)) return StatementRoute.DECRYPT;
        if (ENCRYPT_STATEMENTS.contains(statementId)) return StatementRoute.ENCRYPT;
        return StatementRoute.PASSTHROUGH;
    }

    private boolean dynamicFlagAllows(String statementId, StatementRoute route) {
        if (route == StatementRoute.ADDRESS_BOOK) return true;
        for (String item : configuredInterceptorItems()) {
            if (!item.isEmpty() && statementId.contains(item)) return true;
        }
        return false;
    }

    private List<String> configuredInterceptorItems() {
        List<String> items = new ArrayList<>(Arrays.asList(Constant.INTERCEPTOR_ITEMS.split(",")));
        Object configured = Constant.flagMap.get("interceptorItems");
        if (!Constant.flagMap.isEmpty() && configured != null) {
            items = JSON.parseObject(configured.toString(),
                    new TypeReference<ArrayList<String>>() {});
        }
        return items == null ? Collections.<String>emptyList() : items;
    }

    private static Set<String> immutable(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
}
