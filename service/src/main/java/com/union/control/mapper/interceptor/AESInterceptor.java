package com.union.control.mapper.interceptor;

import com.nucc.channel.ark.common.exception.CheckException;
import com.union.control.service.sensitive.AddressBookPlaintextPolicy;
import com.union.control.service.sensitive.SensitiveFieldCodec;
import com.union.control.service.sensitive.SensitiveRevealPolicy;
import com.union.control.service.sensitive.SensitiveRevealProcessor;
import com.union.control.utils.security.SymmetricalSecurityUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Properties;

@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update", args = {
                MappedStatement.class, Object.class})
})
public class AESInterceptor implements Interceptor {
    private final AESStatementRouter statementRouter = new AESStatementRouter();
    private final MappedStatementCopier statementCopier = new MappedStatementCopier();

    @Autowired(required = false)
    private SensitiveFieldCodec fieldCodec;
    @Autowired(required = false)
    private SensitiveRevealPolicy revealPolicy;
    @Autowired(required = false)
    private SensitiveResultProcessor resultProcessor;
    @Autowired(required = false)
    private AddressBookHandler addressBookHandler;

    public AESInterceptor() {}

    public AESInterceptor(SymmetricalSecurityUtils crypto) {
        this(crypto, new SensitiveRevealPolicy(false), null,
                new AddressBookPlaintextPolicy(""));
    }

    public AESInterceptor(SymmetricalSecurityUtils crypto, SensitiveRevealPolicy revealPolicy,
                          SensitiveRevealProcessor revealProcessor) {
        this(crypto, revealPolicy, revealProcessor, new AddressBookPlaintextPolicy(""));
    }

    public AESInterceptor(SymmetricalSecurityUtils crypto, SensitiveRevealPolicy revealPolicy,
                          SensitiveRevealProcessor revealProcessor,
                          AddressBookPlaintextPolicy plaintextPolicy) {
        SensitiveFieldCodec codec = new SensitiveFieldCodec(crypto);
        AddressBookHandler handler = new AddressBookHandler(codec, revealProcessor, plaintextPolicy);
        initialize(codec, revealPolicy,
                new SensitiveResultProcessor(codec, revealProcessor, handler), handler);
    }

    public AESInterceptor(SensitiveFieldCodec fieldCodec, SensitiveRevealPolicy revealPolicy,
                          SensitiveResultProcessor resultProcessor,
                          AddressBookHandler addressBookHandler) {
        initialize(fieldCodec, revealPolicy, resultProcessor, addressBookHandler);
    }

    private void initialize(SensitiveFieldCodec fieldCodec, SensitiveRevealPolicy revealPolicy,
                            SensitiveResultProcessor resultProcessor,
                            AddressBookHandler addressBookHandler) {
        this.fieldCodec = fieldCodec;
        this.revealPolicy = revealPolicy;
        this.resultProcessor = resultProcessor;
        this.addressBookHandler = addressBookHandler;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        StatementRoute route = statementRouter.resolve(statement.getId());
        if (route == StatementRoute.PASSTHROUGH) return invocation.proceed();

        requireDependencies();
        boolean revealEnabled = revealPolicy.isEnabled();
        resultProcessor.validate(route, revealEnabled);
        prepareInvocation(invocation, statement, route);

        try {
            Object result = invocation.proceed();
            return resultProcessor.process(route, result, revealEnabled);
        } finally {
            if (route.hasSensitiveResult()) {
                ((Executor) invocation.getTarget()).clearLocalCache();
            }
        }
    }

    private void prepareInvocation(Invocation invocation, MappedStatement statement,
                                   StatementRoute route) throws CheckException {
        Object parameter = invocation.getArgs()[1];
        if (route == StatementRoute.ENCRYPT) {
            fieldCodec.encrypt(parameter);
        } else if (route == StatementRoute.DECRYPT) {
            invocation.getArgs()[0] = statementCopier.disableCache(statement, parameter);
        } else if (route == StatementRoute.ADDRESS_BOOK) {
            invocation.getArgs()[0] = addressBookHandler.prepare(statement, parameter);
        }
    }

    private void requireDependencies() {
        if (fieldCodec == null || revealPolicy == null || resultProcessor == null
                || addressBookHandler == null) {
            throw new IllegalStateException("AESInterceptor sensitive dependencies are not configured");
        }
    }

    public static boolean isRevealEligibleStatement(String statementId) {
        return AESStatementRouter.isSensitiveQuery(statementId);
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {}
}
