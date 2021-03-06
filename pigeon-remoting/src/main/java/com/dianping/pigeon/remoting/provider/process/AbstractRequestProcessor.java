/**
 * Dianping.com Inc.
 * Copyright (c) 00-0 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.provider.process;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import com.dianping.pigeon.log.Logger;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.domain.InvocationRequest;
import com.dianping.pigeon.remoting.common.domain.InvocationResponse;
import com.dianping.pigeon.remoting.common.util.Constants;
import com.dianping.pigeon.remoting.provider.domain.ProviderContext;
import com.dianping.pigeon.remoting.provider.listener.RequestTimeoutListener;
import com.dianping.pigeon.remoting.provider.process.threadpool.RequestThreadPoolProcessor;
import com.dianping.pigeon.remoting.provider.util.ProviderUtils;
import com.dianping.pigeon.threadpool.DefaultThreadPool;
import com.dianping.pigeon.threadpool.ThreadPool;
import com.dianping.pigeon.util.ThreadPoolUtils;
import com.dianping.pigeon.util.TimeUtils;

public abstract class AbstractRequestProcessor implements RequestProcessor {

	private static ThreadPool timeCheckThreadPool = new DefaultThreadPool("Pigeon-Provider-Timeout-Checker");

	protected Map<InvocationRequest, ProviderContext> requestContextMap = new ConcurrentHashMap<InvocationRequest, ProviderContext>();

	protected static final Logger logger = LoggerLoader.getLogger(RequestThreadPoolProcessor.class);

	protected RequestTimeoutListener requestTimeoutListener;

	public AbstractRequestProcessor() {
	}

	public abstract Future<InvocationResponse> doProcessRequest(final InvocationRequest request,
			final ProviderContext providerContext);

	public abstract void doStart();

	public void start() {
		requestTimeoutListener = new RequestTimeoutListener(this, requestContextMap);
		timeCheckThreadPool.execute(requestTimeoutListener);
		doStart();
	}

	public abstract void doStop();

	public void stop() {
		ThreadPoolUtils.shutdown(timeCheckThreadPool.getExecutor());
		doStop();
	}

	public Map<InvocationRequest, ProviderContext> getRequestContextMap() {
		return requestContextMap;
	}

	public Future<InvocationResponse> processRequest(final InvocationRequest request,
			final ProviderContext providerContext) {
		if (request.getCreateMillisTime() == 0) {
			request.setCreateMillisTime(TimeUtils.currentTimeMillis());
		}
		Future<InvocationResponse> invocationResponse = null;
		try {
			invocationResponse = doProcessRequest(request, providerContext);
		} catch (Throwable e) {
			String msg = "process request failed:" + request;
			if (request.getCallType() == Constants.CALLTYPE_REPLY
					&& request.getMessageType() != Constants.MESSAGE_TYPE_HEART) {
				providerContext.getChannel().write(ProviderUtils.createFailResponse(request, e));
			}
			// logger.error(msg, e);
		}
		providerContext.setFuture(invocationResponse);
		return invocationResponse;
	}

}
