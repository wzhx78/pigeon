/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.netty.invoker;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import com.dianping.pigeon.event.EventManager;
import com.dianping.pigeon.event.RuntimeServiceEvent;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.domain.InvocationRequest;
import com.dianping.pigeon.remoting.common.domain.InvocationResponse;
import com.dianping.pigeon.remoting.common.util.Constants;
import com.dianping.pigeon.remoting.common.util.ResponseUtils;
import com.dianping.pigeon.remoting.invoker.AbstractClient;
import com.dianping.pigeon.remoting.invoker.Client;
import com.dianping.pigeon.remoting.invoker.domain.Callback;
import com.dianping.pigeon.remoting.invoker.domain.ConnectInfo;
import com.dianping.pigeon.remoting.invoker.domain.InvokerContext;
import com.dianping.pigeon.remoting.invoker.domain.RpcInvokeInfo;
import com.dianping.pigeon.remoting.invoker.util.RpcEventUtils;
import com.dianping.pigeon.remoting.provider.config.ServerConfig;
import com.dianping.pigeon.threadpool.DefaultThreadFactory;

public class NettyClient extends AbstractClient {

	private static final Logger logger = LoggerLoader.getLogger(NettyClient.class);

	private ClientBootstrap bootstrap;

	private Channel channel;

	private String host;

	private int port = ServerConfig.DEFAULT_PORT;

	private String address;

	private static final int connectTimeout = 3000;

	private volatile boolean connected = false;

	private volatile boolean closed = false;

	private volatile boolean active = true;
	private volatile boolean activeSetable = false;

	private ConnectInfo connectInfo;

	public static final int CLIENT_CONNECTIONS = Runtime.getRuntime().availableProcessors();

	private long logCount;

	public NettyClient(ConnectInfo connectInfo) {
		this.host = connectInfo.getHost();
		this.port = connectInfo.getPort();
		this.connectInfo = connectInfo;
		this.address = host + ":" + port;

		ExecutorService bossExecutor = Executors.newCachedThreadPool(new DefaultThreadFactory(
				"Pigeon-Netty-Client-Boss"));

		ExecutorService workExecutor = Executors.newCachedThreadPool(new DefaultThreadFactory(
				"Pigeon-Netty-Client-Worker"));

		this.bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(bossExecutor, workExecutor));
		this.bootstrap.setPipelineFactory(new NettyClientPipelineFactory(this));
		this.bootstrap.setOption("tcpNoDelay", true);
		this.bootstrap.setOption("keepAlive", true);
		this.bootstrap.setOption("reuseAddress", true);
		this.bootstrap.setOption("connectTimeoutMillis", 1000);
	}

	public synchronized void connect() {
		if (this.connected || this.closed) {
			resetLogCount();
			return;
		}
		incLogCount();
		if (logger.isInfoEnabled() && isLog()) {
			logger.info("client is connecting to " + this.host + ":" + this.port);
		}
		ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));
		if (future.awaitUninterruptibly(connectTimeout, TimeUnit.MILLISECONDS)) {
			if (future.isSuccess()) {
				Channel newChannel = future.getChannel();
				try {
					// 关闭旧的连接
					Channel oldChannel = this.channel;
					if (oldChannel != null) {
						if (logger.isInfoEnabled()) {
							logger.info("close old netty channel " + oldChannel);
						}
						try {
							oldChannel.close();
						} catch (Throwable t) {
						}
					}
				} finally {
					this.channel = newChannel;
				}
				logger.warn("client is connected to " + this.host + ":" + this.port);
				this.connected = true;
				resetLogCount();
			} else if (isLog()) {
				logger.error("client is not connected to " + this.host + ":" + this.port);
			}
		} else if (isLog()) {
			logger.error("timeout while connecting to " + this.host + ":" + this.port);
		}
	}

	public InvocationResponse write(InvocationRequest request) {
		return write(request, null);
	}

	public InvocationResponse write(InvocationRequest request, Callback callback) {
		Object[] msg = new Object[] { request, callback };
		ChannelFuture future = null;
		if (channel == null) {
			logger.error("channel:" + null + " ^^^^^^^^^^^^^^");
		} else {
			future = channel.write(msg);
			if (request.getMessageType() == Constants.MESSAGE_TYPE_SERVICE
					|| request.getMessageType() == Constants.MESSAGE_TYPE_HEART) {
				future.addListener(new MsgWriteListener(request));
			}
		}
		return null;
	}

	public void connectionException(Object attachment, Throwable e) {
		this.connected = false;
		connectionException(this, attachment, e);
	}

	private void connectionException(Client client, Object attachment, Throwable e) {
		if (isLog()) {
			logger.error("exception while connecting to :" + client + ", exception:" + e.getMessage());
		}
		if (attachment == null) {
			return;
		}
		Object[] msg = (Object[]) attachment;
		if (msg[0] instanceof InvokerContext) {
			InvokerContext invokerContext = (InvokerContext) msg[0];
			InvocationRequest request = invokerContext.getRequest();
			if (request.getMessageType() == Constants.MESSAGE_TYPE_SERVICE && msg[1] != null) {
				try {
					Callback callback = (Callback) msg[1];
					if (client != null) {
						error(request, client);
						client.write(request, callback);
					} else {
						logger.error("no client for use to " + request.getServiceName());
					}
				} catch (Exception ne) {
					logger.error(ne.getMessage(), ne);
				}
				logger.error(e.getMessage(), e);
			}
		}
	}

	private void error(InvocationRequest request, Client client) {
		if (EventManager.IS_EVENT_ENABLED) {
			RpcInvokeInfo rpcInvokeInfo = new RpcInvokeInfo();
			rpcInvokeInfo.setServiceName(request.getServiceName());
			rpcInvokeInfo.setAddressIp(client.getAddress());
			rpcInvokeInfo.setRequest(request);
			RuntimeServiceEvent event = new RuntimeServiceEvent(
					RuntimeServiceEvent.Type.RUNTIME_RPC_INVOKE_CONNECT_EXCEPTION, rpcInvokeInfo);
			EventManager.getInstance().publishEvent(event);
		}
	}

	/**
	 * @return the connected
	 */
	public boolean isConnected() {
		return connected;
	}

	public boolean isActive() {
		return active;
	}

	public void setActive(boolean active) {
		if (this.activeSetable) {
			this.active = active;
		}
	}

	public void setActiveSetable(boolean activeSetable) {
		this.activeSetable = activeSetable;
	}

	@Override
	public boolean isWritable() {
		return this.channel.isWritable();
	}

	/**
	 * @return the host
	 */
	public String getHost() {
		return host;
	}

	public int getPort() {

		return this.port;
	}

	/**
	 * @return the address
	 */
	public String getAddress() {
		return address;
	}

	public boolean equals(Object obj) {
		if (obj instanceof NettyClient) {
			NettyClient nc = (NettyClient) obj;
			return this.address.equals(nc.getAddress());
		} else {
			return super.equals(obj);
		}
	}

	@Override
	public int hashCode() {
		return address.hashCode();
	}

	@Override
	public void close() {
		closed = true;
		channel.close();
	}

	@Override
	public String toString() {
		return this.getAddress() + ",is connected:" + this.isConnected();
	}

	public class MsgWriteListener implements ChannelFutureListener {

		private InvocationRequest request;

		public MsgWriteListener(InvocationRequest request) {
			this.request = request;
		}

		public void operationComplete(ChannelFuture future) throws Exception {
			if (future.isSuccess()) {
				return;
			}
			if (request.getMessageType() != Constants.MESSAGE_TYPE_HEART) {
				connected = false;
			}

			RpcEventUtils.channelOperationComplete(request, NettyClient.this.address);
			InvocationResponse response = ResponseUtils.createFailResponse(request, future.getCause());
			processResponse(response);
		}

	}

	@Override
	public ConnectInfo getConnectInfo() {
		return connectInfo;
	}

	private void resetLogCount() {
		logCount = 0;
	}

	private boolean isLog() {
		boolean isLog = true;
		if (logCount > 100 && logCount % 100 != 0) {
			isLog = false;
		}
		return isLog;
	}

	private void incLogCount() {
		logCount = logCount + 1;
	}

	@Override
	public boolean isDisposable() {
		return false;
	}

	@Override
	public void dispose() {

	}
}
