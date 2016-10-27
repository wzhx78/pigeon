/**
 * Dianping.com Inc.
 * Copyright (c) 00-0 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.provider.process.threadpool;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.dianping.pigeon.remoting.common.codec.json.JacksonSerializer;
import com.dianping.pigeon.remoting.provider.config.spring.PoolBean;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;

import com.dianping.pigeon.config.ConfigChangeListener;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.log.Logger;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.domain.InvocationContext.TimePhase;
import com.dianping.pigeon.remoting.common.domain.InvocationContext.TimePoint;
import com.dianping.pigeon.remoting.common.domain.InvocationRequest;
import com.dianping.pigeon.remoting.common.domain.InvocationResponse;
import com.dianping.pigeon.remoting.common.exception.RejectedException;
import com.dianping.pigeon.remoting.common.process.ServiceInvocationHandler;
import com.dianping.pigeon.remoting.provider.config.ProviderConfig;
import com.dianping.pigeon.remoting.provider.config.ProviderMethodConfig;
import com.dianping.pigeon.remoting.provider.config.ServerConfig;
import com.dianping.pigeon.remoting.provider.domain.ProviderContext;
import com.dianping.pigeon.remoting.provider.process.AbstractRequestProcessor;
import com.dianping.pigeon.remoting.provider.process.ProviderProcessHandlerFactory;
import com.dianping.pigeon.remoting.provider.process.filter.GatewayProcessFilter;
import com.dianping.pigeon.remoting.provider.service.method.ServiceMethodCache;
import com.dianping.pigeon.remoting.provider.service.method.ServiceMethodFactory;
import com.dianping.pigeon.threadpool.DefaultThreadPool;
import com.dianping.pigeon.threadpool.ThreadPool;
import com.dianping.pigeon.util.CollectionUtils;

public class RequestThreadPoolProcessor extends AbstractRequestProcessor {

    private static final Logger logger = LoggerLoader.getLogger(RequestThreadPoolProcessor.class);

    private static final String poolStrategy = ConfigManagerLoader.getConfigManager().getStringValue(
            "pigeon.provider.pool.strategy", "shared");

    private static ThreadPool sharedRequestProcessThreadPool = null;

    private static final int SLOW_POOL_CORESIZE = ConfigManagerLoader.getConfigManager().getIntValue(
            "pigeon.provider.pool.slow.coresize", 30);

    private static final int SLOW_POOL_MAXSIZE = ConfigManagerLoader.getConfigManager().getIntValue(
            "pigeon.provider.pool.slow.maxsize", 200);

    private static final int SLOW_POOL_QUEUESIZE = ConfigManagerLoader.getConfigManager().getIntValue(
            "pigeon.provider.pool.slow.queuesize", 500);

    private static ThreadPool slowRequestProcessThreadPool = new DefaultThreadPool(
            "Pigeon-Server-Slow-Request-Processor", SLOW_POOL_CORESIZE, SLOW_POOL_MAXSIZE,
            new LinkedBlockingQueue<Runnable>(SLOW_POOL_QUEUESIZE));

    private ThreadPool requestProcessThreadPool = null;

    private ConcurrentHashMap<String, ThreadPool> methodThreadPools = null;

    private ConcurrentHashMap<String, ThreadPool> serviceThreadPools = null;

    private static int DEFAULT_POOL_ACTIVES = ConfigManagerLoader.getConfigManager().getIntValue(
            "pigeon.provider.pool.actives", 60);

    private static float DEFAULT_POOL_RATIO_CORE = ConfigManagerLoader.getConfigManager().getFloatValue(
            "pigeon.provider.pool.ratio.coresize", 3f);

    private static float cancelRatio = ConfigManagerLoader.getConfigManager().getFloatValue(
            "pigeon.timeout.cancelratio", 1f);

    private static Map<String, String> methodPoolConfigKeys = new HashMap<String, String>();

    private static String sharedPoolCoreSizeKey = null;

    private static String sharedPoolMaxSizeKey = null;

    private static String sharedPoolQueueSizeKey = null;

    private static boolean enableSlowPool = ConfigManagerLoader.getConfigManager().getBooleanValue(
            "pigeon.provider.pool.slow.enable", true);

    private static final JacksonSerializer jacksonSerializer = new JacksonSerializer();
    private static final String KEY_PROVIDER_POOL_CONFIG = "pigeon.provider.pool.config";
    private static final String KEY_PROVIDER_POOL_API_CONFIG = "pigeon.provider.pool.api.config";
    // poolName --> poolBean
    private volatile static Map<String, PoolBean> poolNameMapping = Maps.newConcurrentMap();
    // url or url#method --> poolName
    private volatile static Map<String, String> apiPoolConfigMapping = Maps.newConcurrentMap();

    static {
        try {
            init();
        } catch (Throwable t) {
            throw new RuntimeException("failed to init pool config! please check!", t);
        }
    }

    public RequestThreadPoolProcessor(ServerConfig serverConfig) {
        ConfigManagerLoader.getConfigManager().registerConfigChangeListener(new InnerConfigChangeListener());
        if ("server".equals(poolStrategy)) {
            requestProcessThreadPool = new DefaultThreadPool("Pigeon-Server-Request-Processor-"
                    + serverConfig.getProtocol() + "-" + serverConfig.getActualPort(), serverConfig.getCorePoolSize(),
                    serverConfig.getMaxPoolSize(), new LinkedBlockingQueue<Runnable>(serverConfig.getWorkQueueSize()));
        } else {
            sharedRequestProcessThreadPool = new DefaultThreadPool("Pigeon-Server-Request-Processor",
                    serverConfig.getCorePoolSize(), serverConfig.getMaxPoolSize(), new LinkedBlockingQueue<Runnable>(
                    serverConfig.getWorkQueueSize()));
            requestProcessThreadPool = sharedRequestProcessThreadPool;
        }
    }

    private static void init() throws Throwable {
        String poolConfig = ConfigManagerLoader.getConfigManager().getStringValue(KEY_PROVIDER_POOL_CONFIG, "");
        refreshPoolConfig(poolConfig);

        String apiPoolConfig = ConfigManagerLoader.getConfigManager().getStringValue(KEY_PROVIDER_POOL_API_CONFIG, "");
        refreshApiPoolConfig(apiPoolConfig);
    }

    private static void refreshPoolConfig(String poolConfig) throws Throwable {
        if (StringUtils.isNotBlank(poolConfig)) {
            PoolBean[] poolBeen = (PoolBean[])jacksonSerializer.toObject(PoolBean[].class, poolConfig);
            Map<String, PoolBean> _poolNameMapping = Maps.newConcurrentMap();

            for (PoolBean poolBean : poolBeen) {
                if(poolBean.checkNotNull()) {
                    _poolNameMapping.put(poolBean.getPoolName(), poolBean);
                } else {//报异常,保持原状
                    throw new RuntimeException("pool config error! please check: " + poolBean);
                }
            }

            if(_poolNameMapping.size() != poolBeen.length) {//报异常,保持原状
                throw new RuntimeException("conflict pool name exists, please check!");
            } else {
                List<PoolBean> poolBeenToClose = Lists.newLinkedList();
                for (Map.Entry<String, PoolBean> oldPoolBeanEntry : poolNameMapping.entrySet()) {
                    String oldPoolName = oldPoolBeanEntry.getKey();
                    PoolBean oldPoolBean = oldPoolBeanEntry.getValue();
                    PoolBean _poolBean = _poolNameMapping.get(oldPoolName);
                    if (oldPoolBean.equals(_poolBean)) {
                        _poolNameMapping.remove(oldPoolName);
                        _poolNameMapping.put(oldPoolName, oldPoolBean);
                    } else {
                        poolBeenToClose.add(oldPoolBean);
                    }
                }
                poolNameMapping = _poolNameMapping;
                for (PoolBean poolBeanToClose : poolBeenToClose) {
                    poolBeanToClose.closeThreadPool();
                }
            }
        }
    }

    private static void refreshApiPoolConfig(String servicePoolConfig) throws Throwable {
        if (StringUtils.isNotBlank(servicePoolConfig)) {
            Map<String, String> _servicePoolConfigMapping = (Map) jacksonSerializer.toObject(Map.class, servicePoolConfig);
            apiPoolConfigMapping = new ConcurrentHashMap<>(_servicePoolConfigMapping);
        }
    }

    public void doStop() {
    }

    public Future<InvocationResponse> doProcessRequest(final InvocationRequest request,
                                                       final ProviderContext providerContext) {
        requestContextMap.put(request, providerContext);
        Callable<InvocationResponse> requestExecutor = new Callable<InvocationResponse>() {

            @Override
            public InvocationResponse call() throws Exception {
                providerContext.getTimeline().add(new TimePoint(TimePhase.T));
                try {
                    ServiceInvocationHandler invocationHandler = ProviderProcessHandlerFactory
                            .selectInvocationHandler(providerContext.getRequest().getMessageType());
                    if (invocationHandler != null) {
                        providerContext.setThread(Thread.currentThread());
                        return invocationHandler.handle(providerContext);
                    }
                } catch (Throwable t) {
                    logger.error("Process request failed with invocation handler, you should never be here.", t);
                } finally {
                    requestContextMap.remove(request);
                }
                return null;
            }
        };
        final ThreadPool pool = selectThreadPool(request);
        // MonitorTransaction transaction =
        // monitor.createTransaction("PigeonRequestSubmit", "",
        // providerContext);
        // transaction.setStatusOk();
        try {
            checkRequest(pool, request);
            providerContext.getTimeline().add(new TimePoint(TimePhase.T));
            return pool.submit(requestExecutor);
        } catch (RejectedExecutionException e) {
            // transaction.setStatusError(e);
            requestContextMap.remove(request);
            throw new RejectedException(getProcessorStatistics(request), e);
        }
        // finally {
        // transaction.complete();
        // }
    }

    private void checkRequest(final ThreadPool pool, final InvocationRequest request) {
        GatewayProcessFilter.checkRequest(request);
    }

    private ThreadPool selectThreadPool(final InvocationRequest request) {
        ThreadPool pool = null;

        // spring配置方式
        if (!CollectionUtils.isEmpty(methodThreadPools)) {
            pool = methodThreadPools.get(request.getServiceName() + "#" + request.getMethodName());
        }
        if (pool == null && !CollectionUtils.isEmpty(serviceThreadPools)) {
            pool = serviceThreadPools.get(request.getServiceName());
        }

        // 配置中心方式
        if (pool == null && !CollectionUtils.isEmpty(apiPoolConfigMapping)) {
            String poolName = apiPoolConfigMapping.get(request.getServiceName() + "#" + request.getMethodName());
            if (StringUtils.isNotBlank(poolName)) { // 方法级别
                PoolBean poolBean = poolNameMapping.get(poolName);
                if(poolBean != null) {
                    pool = poolBean.getThreadPool();
                }
            } else { // 服务级别
                poolName = apiPoolConfigMapping.get(request.getServiceName());
                if (StringUtils.isNotBlank(poolName)) {
                    PoolBean poolBean = poolNameMapping.get(poolName);
                    if(poolBean != null) {
                        pool = poolBean.getThreadPool();
                    }
                }
            }
        }

        // 默认方式
        if (pool == null) {
            if (enableSlowPool && requestTimeoutListener.isSlowRequest(request)) {
                pool = slowRequestProcessThreadPool;
            } else {
                if ("server".equals(poolStrategy)) {
                    pool = requestProcessThreadPool;
                } else {
                    pool = sharedRequestProcessThreadPool;
                }
            }
        }

        return pool;
    }

    @Override
    public String getProcessorStatistics() {
        StringBuilder stats = new StringBuilder();

        //todo 增加lion配置方式的统计信息

        if ("server".equals(poolStrategy)) {
            stats.append("[server=").append(getThreadPoolStatistics(requestProcessThreadPool)).append("]");
        } else {
            stats.append("[shared=").append(getThreadPoolStatistics(sharedRequestProcessThreadPool)).append("]");
        }
        stats.append("[slow=").append(getThreadPoolStatistics(slowRequestProcessThreadPool)).append("]");
        if (!CollectionUtils.isEmpty(serviceThreadPools)) {
            for (String key : serviceThreadPools.keySet()) {
                stats.append(",[").append(key).append("=").append(getThreadPoolStatistics(serviceThreadPools.get(key)))
                        .append("]");
            }
        }
        if (!CollectionUtils.isEmpty(methodThreadPools)) {
            for (String key : methodThreadPools.keySet()) {
                stats.append(",[").append(key).append("=").append(getThreadPoolStatistics(methodThreadPools.get(key)))
                        .append("]");
            }
        }
        stats.append(GatewayProcessFilter.getStatistics());
        return stats.toString();
    }

    private boolean needStandalonePool(ProviderConfig<?> providerConfig) {
        return !providerConfig.isUseSharedPool() || "method".equals(poolStrategy);
    }

    @Override
    public synchronized <T> void addService(ProviderConfig<T> providerConfig) {
        String url = providerConfig.getUrl();
        Map<String, ProviderMethodConfig> methodConfigs = providerConfig.getMethods();
        ServiceMethodCache methodCache = ServiceMethodFactory.getServiceMethodCache(url);
        Set<String> methodNames = methodCache.getMethodMap().keySet();
        if (needStandalonePool(providerConfig)) {
            if (methodThreadPools == null) {
                methodThreadPools = new ConcurrentHashMap<String, ThreadPool>();
            }
            if (serviceThreadPools == null) {
                serviceThreadPools = new ConcurrentHashMap<String, ThreadPool>();
            }

            if (providerConfig.getPoolBean() != null && CollectionUtils.isEmpty(methodConfigs)) {
                serviceThreadPools.putIfAbsent(url, providerConfig.getPoolBean().getThreadPool());

            } else if (providerConfig.getActives() > 0 && CollectionUtils.isEmpty(methodConfigs)) {
                ThreadPool pool = serviceThreadPools.get(url);
                if (pool == null) {
                    int actives = providerConfig.getActives();
                    int coreSize = (int) (actives / DEFAULT_POOL_RATIO_CORE) > 0 ? (int) (actives / DEFAULT_POOL_RATIO_CORE)
                            : actives;
                    int maxSize = actives;
                    int queueSize = actives;
                    pool = new DefaultThreadPool("Pigeon-Server-Request-Processor-service", coreSize, maxSize,
                            new LinkedBlockingQueue<Runnable>(queueSize));
                    serviceThreadPools.putIfAbsent(url, pool);
                }

            } else if (!CollectionUtils.isEmpty(methodConfigs)) {
                for (String name : methodNames) {
                    if (!methodConfigs.containsKey(name)) {
                        continue;
                    }
                    String key = url + "#" + name;
                    ThreadPool pool = methodThreadPools.get(key);
                    if (pool == null) {

                        ProviderMethodConfig methodConfig = methodConfigs.get(name);
                        if(methodConfig != null) {
                            if (methodConfig.getPoolBean() != null) {
                                methodThreadPools.putIfAbsent(key, methodConfig.getPoolBean().getThreadPool());

                            } else {
                                int actives = DEFAULT_POOL_ACTIVES;
                                if (methodConfig.getActives() > 0) {
                                    actives = methodConfig.getActives();
                                }
                                int coreSize = (int) (actives / DEFAULT_POOL_RATIO_CORE) > 0 ? (int) (actives / DEFAULT_POOL_RATIO_CORE)
                                        : actives;
                                int maxSize = actives;
                                int queueSize = actives;
                                pool = new DefaultThreadPool("Pigeon-Server-Request-Processor-method", coreSize, maxSize,
                                        new LinkedBlockingQueue<Runnable>(queueSize));
                                methodThreadPools.putIfAbsent(key, pool);
                            }
                        }

                    }
                }
            }
        }
    }

    @Override
    public String getProcessorStatistics(InvocationRequest request) {
        ThreadPool pool = selectThreadPool(request);
        return getThreadPoolStatistics(pool);
    }

    private String getThreadPoolStatistics(ThreadPool pool) {
        if (pool == null) {
            return null;
        }
        ThreadPoolExecutor e = pool.getExecutor();
        String stats = String.format(
                "request pool size:%d(active:%d,core:%d,max:%d,largest:%d),task count:%d(completed:%d),queue size:%d,queue remaining:%d",
                e.getPoolSize(), e.getActiveCount(), e.getCorePoolSize(), e.getMaximumPoolSize(), e.getLargestPoolSize(),
                e.getTaskCount(), e.getCompletedTaskCount(), e.getQueue().size(), e.getQueue().remainingCapacity());
        return stats;
    }

    @Override
    public synchronized <T> void removeService(ProviderConfig<T> providerConfig) {
        if (needStandalonePool(providerConfig)) {
            Set<String> toRemoveKeys = new HashSet<String>();
            for (String key : methodThreadPools.keySet()) {
                if (key.startsWith(providerConfig.getUrl() + "#")) {
                    toRemoveKeys.add(key);
                }
            }
            for (String key : toRemoveKeys) {
                ThreadPool pool = methodThreadPools.remove(key);
                if (pool != null) {
                    pool.getExecutor().shutdown();
                }
            }
            ThreadPool pool = serviceThreadPools.remove(providerConfig.getUrl());
            if (pool != null) {
                pool.getExecutor().shutdown();
            }
        }
    }

    @Override
    public boolean needCancelRequest(InvocationRequest request) {
        ThreadPool pool = selectThreadPool(request);
        return pool.getExecutor().getPoolSize() >= pool.getExecutor().getMaximumPoolSize() * cancelRatio;
    }

    @Override
    public ThreadPool getRequestProcessThreadPool() {
        ThreadPool pool;

        if ("server".equals(poolStrategy)) {
            pool = requestProcessThreadPool;
        } else {
            pool = sharedRequestProcessThreadPool;
        }

        return pool;
    }

    @Override
    public void doStart() {

    }

    public static Map<String, String> getMethodPoolConfigKeys() {
        return methodPoolConfigKeys;
    }

    public static void setSharedPoolQueueSizeKey(String sharedPoolQueueSizeKey) {
        RequestThreadPoolProcessor.sharedPoolQueueSizeKey = sharedPoolQueueSizeKey;
    }

    public static void setSharedPoolMaxSizeKey(String sharedPoolMaxSizeKey) {
        RequestThreadPoolProcessor.sharedPoolMaxSizeKey = sharedPoolMaxSizeKey;
    }

    public static void setSharedPoolCoreSizeKey(String sharedPoolCoreSizeKey) {
        RequestThreadPoolProcessor.sharedPoolCoreSizeKey = sharedPoolCoreSizeKey;
    }

    private class InnerConfigChangeListener implements ConfigChangeListener {

        @Override
        public void onKeyUpdated(String key, String value) {
            if (key.endsWith(KEY_PROVIDER_POOL_CONFIG)) {
                try {
                    refreshPoolConfig(value);
                } catch (Throwable t) {
                    logger.warn("failed to refresh pool config, fallback to previous settings, please check...", t);
                }
            } else if (key.endsWith(KEY_PROVIDER_POOL_API_CONFIG)) {
                try {
                    refreshApiPoolConfig(value);
                } catch (Throwable t) {
                    logger.warn("failed to refresh api pool config, fallback to previous settings, please check...", t);
                }
            } else if (key.endsWith("pigeon.provider.pool.slow.enable")) {
                enableSlowPool = Boolean.valueOf(value);
            } else if (key.endsWith("pigeon.timeout.cancelratio")) {
                cancelRatio = Float.valueOf(value);
            } else if (key.endsWith("pigeon.provider.pool.ratio.coresize")) {
                DEFAULT_POOL_RATIO_CORE = Float.valueOf(value);
            } else if (StringUtils.isNotBlank(sharedPoolCoreSizeKey) && key.endsWith(sharedPoolCoreSizeKey)) {
                int size = Integer.valueOf(value);
                if (size != sharedRequestProcessThreadPool.getExecutor().getCorePoolSize() && size >= 0) {
                    try {
                        ThreadPool oldPool = sharedRequestProcessThreadPool;
                        int queueSize = oldPool.getExecutor().getQueue().remainingCapacity()
                                + oldPool.getExecutor().getQueue().size();
                        try {
                            ThreadPool newPool = new DefaultThreadPool("Pigeon-Server-Request-Processor-method", size,
                                    oldPool.getExecutor().getMaximumPoolSize(), new LinkedBlockingQueue<Runnable>(
                                    queueSize));
                            sharedRequestProcessThreadPool = newPool;
                            oldPool.getExecutor().shutdown();
                            oldPool.getExecutor().awaitTermination(5, TimeUnit.SECONDS);
                            oldPool = null;
                        } catch (Throwable e) {
                            logger.warn("error when shuting down old shared pool", e);
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("changed shared pool, key:" + key + ", value:" + value);
                        }
                    } catch (RuntimeException e) {
                        logger.error("error while changing shared pool, key:" + key + ", value:" + value, e);
                    }
                }
            } else if (StringUtils.isNotBlank(sharedPoolMaxSizeKey) && key.endsWith(sharedPoolMaxSizeKey)) {
                int size = Integer.valueOf(value);
                if (size != sharedRequestProcessThreadPool.getExecutor().getMaximumPoolSize() && size >= 0) {
                    try {
                        ThreadPool oldPool = sharedRequestProcessThreadPool;
                        int queueSize = oldPool.getExecutor().getQueue().remainingCapacity()
                                + oldPool.getExecutor().getQueue().size();
                        try {
                            ThreadPool newPool = new DefaultThreadPool("Pigeon-Server-Request-Processor-method",
                                    oldPool.getExecutor().getCorePoolSize(), size, new LinkedBlockingQueue<Runnable>(
                                    queueSize));
                            sharedRequestProcessThreadPool = newPool;
                            oldPool.getExecutor().shutdown();
                            oldPool.getExecutor().awaitTermination(5, TimeUnit.SECONDS);
                            oldPool = null;
                        } catch (Throwable e) {
                            logger.warn("error when shuting down old shared pool", e);
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("changed shared pool, key:" + key + ", value:" + value);
                        }
                    } catch (RuntimeException e) {
                        logger.error("error while changing shared pool, key:" + key + ", value:" + value, e);
                    }
                }
            } else if (StringUtils.isNotBlank(sharedPoolQueueSizeKey) && key.endsWith(sharedPoolQueueSizeKey)) {
                int size = Integer.valueOf(value);
                ThreadPool oldPool = sharedRequestProcessThreadPool;
                int queueSize = oldPool.getExecutor().getQueue().remainingCapacity()
                        + oldPool.getExecutor().getQueue().size();
                if (size != queueSize && size >= 0) {
                    try {
                        try {
                            ThreadPool newPool = new DefaultThreadPool("Pigeon-Server-Request-Processor-method",
                                    oldPool.getExecutor().getCorePoolSize(),
                                    oldPool.getExecutor().getMaximumPoolSize(), new LinkedBlockingQueue<Runnable>(size));
                            sharedRequestProcessThreadPool = newPool;
                            oldPool.getExecutor().shutdown();
                            oldPool.getExecutor().awaitTermination(5, TimeUnit.SECONDS);
                            oldPool = null;
                        } catch (Throwable e) {
                            logger.warn("error when shuting down old shared pool", e);
                        }
                        if (logger.isInfoEnabled()) {
                            logger.info("changed shared pool, key:" + key + ", value:" + value);
                        }
                    } catch (RuntimeException e) {
                        logger.error("error while changing shared pool, key:" + key + ", value:" + value, e);
                    }
                }
            } else {
                for (String k : methodPoolConfigKeys.keySet()) {
                    String v = methodPoolConfigKeys.get(k);
                    if (key.endsWith(v)) {
                        try {
                            String serviceKey = k;
                            if (StringUtils.isNotBlank(serviceKey)) {
                                ThreadPool pool = null;
                                if (!CollectionUtils.isEmpty(methodThreadPools)) {
                                    pool = methodThreadPools.get(serviceKey);
                                    int actives = Integer.valueOf(value);
                                    if (pool != null && actives != pool.getExecutor().getMaximumPoolSize()
                                            && actives >= 0) {
                                        int coreSize = (int) (actives / DEFAULT_POOL_RATIO_CORE) > 0 ? (int) (actives / DEFAULT_POOL_RATIO_CORE)
                                                : actives;
                                        int queueSize = actives;
                                        int maxSize = actives;
                                        try {
                                            ThreadPool newPool = new DefaultThreadPool(
                                                    "Pigeon-Server-Request-Processor-method", coreSize, maxSize,
                                                    new LinkedBlockingQueue<Runnable>(queueSize));
                                            methodThreadPools.put(serviceKey, newPool);
                                            pool.getExecutor().shutdown();
                                            pool.getExecutor().awaitTermination(5, TimeUnit.SECONDS);
                                            pool = null;
                                        } catch (Throwable e) {
                                            logger.warn("error when shuting down old method pool", e);
                                        }
                                        if (logger.isInfoEnabled()) {
                                            logger.info("changed method pool, key:" + serviceKey + ", value:" + actives);
                                        }
                                    }
                                }
                            }
                        } catch (RuntimeException e) {
                            logger.error("error while changing method pool, key:" + key + ", value:" + value, e);
                        }
                    }
                }
            }
        }

        @Override
        public void onKeyAdded(String key, String value) {
        }

        @Override
        public void onKeyRemoved(String key) {
        }

    }
}
