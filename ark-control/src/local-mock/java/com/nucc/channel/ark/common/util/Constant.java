package com.nucc.channel.ark.common.util;

import java.util.HashMap;
import java.util.Map;

/** Local compatibility subset. Production keeps its existing Constant class unchanged. */
public final class Constant {
    private Constant() {}

    @SuppressWarnings("rawtypes")
    public static final Map flagMap = new HashMap();

    public static final String REGEX_MOBILE = "(?<!\\d)1[3-9]\\d{9}(?!\\d)";
    public static final String REGEX_EMAIL = "[a-zA-Z0-9.%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
    public static final String REGEX_TELEPHONE = "(?<!\\d)0\\d{2,3}-?\\d{7,8}(?!\\d)";
    public static final String ENCRYPT_START = "#[";
    public static final String ENCRYPT_END = "]";
    public static final String REGEX_DECRYPT_TAG = "#\\[([^#\\]]*)\\]";
    public static final String INTERCEPTOR_ITEMS =
            "SensitiveDataDemoMapper,AnnounceAddressBookRecordMapper,ReformTrackMapper,"
                    + "QuestionnaireMapper,"
                    + "AnnounceMailSendMapper,AnnounceMailMapper,AppealMapper,TicketMapper,"
                    + "NotifyMapper,MailDetailMapper";
}
