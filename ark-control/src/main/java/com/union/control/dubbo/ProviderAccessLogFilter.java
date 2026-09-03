package com.union.control.dubbo;

import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class ProviderAccessLogFilter implements Filter {
    private static final Logger LOG = LoggerFactory.getLogger(ProviderAccessLogFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String service = invoker.getInterface().getSimpleName();
        String method = invocation.getMethodName();
        InetSocketAddress remoteAddress = RpcContext.getContext().getRemoteAddress();
        String remote = String.valueOf(remoteAddress);
        long started = System.nanoTime();
        LOG.info("Dubbo provider request started: service={}, method={}, remote={}",
                service, method, remote);
        try {
            Result result = invoker.invoke(invocation);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (result.hasException()) {
                LOG.warn("Dubbo provider request failed: service={}, method={}, elapsedMs={}",
                        service, method, elapsedMs, result.getException());
            } else {
                LOG.info("Dubbo provider request completed: service={}, method={}, elapsedMs={}",
                        service, method, elapsedMs);
            }
            return result;
        } catch (RpcException error) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            LOG.warn("Dubbo provider request failed: service={}, method={}, elapsedMs={}",
                    service, method, elapsedMs, error);
            throw error;
        }
    }
}
