package com.emc.mongoose.core.impl.load.executor;
//
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.io.IOWorker;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.net.http.conn.pool.HttpConnPool;
import com.emc.mongoose.common.net.http.conn.pool.FixedRouteSequencingConnPool;
import com.emc.mongoose.common.net.http.request.HostHeaderSetter;
import com.emc.mongoose.common.net.http.request.SharedHeadersAdder;
//
import com.emc.mongoose.core.api.container.Container;
import com.emc.mongoose.core.api.data.WSObject;
import com.emc.mongoose.core.api.data.model.ItemSrc;
import com.emc.mongoose.core.api.io.req.RequestConfig;
import com.emc.mongoose.core.api.io.req.WSRequestConfig;
import com.emc.mongoose.core.api.io.task.IOTask;
import com.emc.mongoose.core.api.io.task.WSContainerIOTask;
import com.emc.mongoose.core.api.load.executor.WSContainerLoadExecutor;
//
import com.emc.mongoose.core.impl.io.task.BasicWSContainerTask;
import com.emc.mongoose.core.impl.load.tasks.HttpClientRunTask;
//
import org.apache.http.ExceptionLogger;
import org.apache.http.HttpHost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.nio.pool.BasicNIOPoolEntry;
import org.apache.http.message.HeaderGroup;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestUserAgent;
//
import org.apache.http.impl.nio.DefaultHttpClientIODispatch;
import org.apache.http.impl.nio.pool.BasicNIOConnFactory;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.NHttpClientConnection;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.pool.NIOConnFactory;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequester;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.util.DirectByteBufferAllocator;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 20.10.15.
 */
public class BasicWSContainerLoadExecutor<T extends WSObject, C extends Container<T>>
extends LimitedRateLoadExecutorBase<C>
implements WSContainerLoadExecutor<T, C> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected final IOTask.Type loadType;
	private final HttpProcessor httpProcessor;
	private final HttpAsyncRequester client;
	private final ConnectingIOReactor ioReactor;
	private final Map<HttpHost, HttpConnPool<HttpHost, BasicNIOPoolEntry>> connPoolMap;
	private final WSRequestConfig wsReqConfigCopy;
	private final boolean isPipeliningEnabled;
	//
	public BasicWSContainerLoadExecutor(
		final RunTimeConfig rtConfig, final RequestConfig<C> reqConfig, final String[] addrs,
		final int connCountPerNode, final int threadCount, final ItemSrc<C> itemSrc,
		final long maxCount, final int manualTaskSleepMicroSecs, final float rateLimit
	) throws ClassCastException {
		super(
			rtConfig, reqConfig, addrs, connCountPerNode, threadCount, itemSrc, maxCount,
			manualTaskSleepMicroSecs, rateLimit
		);
		//
		this.loadType = reqConfig.getLoadType();
		wsReqConfigCopy = (WSRequestConfig) reqConfigCopy;
		isPipeliningEnabled = wsReqConfigCopy.getPipelining();
		//
		if(IOTask.Type.READ.equals(loadType)) {
			reqConfig.setBuffSize(Constants.BUFF_SIZE_HI);
		} else {
			reqConfig.setBuffSize(Constants.BUFF_SIZE_LO);
		}
		//
		final HeaderGroup sharedHeaders = wsReqConfigCopy.getSharedHeaders();
		final String userAgent = rtConfig.getRunName() + "/" + rtConfig.getRunVersion();
		//
		httpProcessor = HttpProcessorBuilder
			.create()
			.add(new SharedHeadersAdder(sharedHeaders))
			.add(new HostHeaderSetter())
			.add(new RequestConnControl())
			.add(new RequestUserAgent(userAgent))
				//.add(new RequestExpectContinue(true))
			.add(new RequestContent(false))
			.build();
		client = new HttpAsyncRequester(
			httpProcessor, DefaultConnectionReuseStrategy.INSTANCE,
			new ExceptionLogger() {
				@Override
				public final void log(final Exception e) {
					LogUtil.exception(LOG, Level.DEBUG, e, "HTTP client internal failure");
				}
			}
		);
		//
		final RunTimeConfig thrLocalConfig = RunTimeConfig.getContext();
		final long timeOutMs = rtConfig.getLoadLimitTimeUnit().toMillis(
			rtConfig.getLoadLimitTimeValue()
		);
		final IOReactorConfig.Builder ioReactorConfigBuilder = IOReactorConfig
			.custom()
			.setIoThreadCount(threadCount)
			.setBacklogSize((int) thrLocalConfig.getSocketBindBackLogSize())
			.setInterestOpQueued(thrLocalConfig.getSocketInterestOpQueued())
			.setSelectInterval(thrLocalConfig.getSocketSelectInterval())
			.setShutdownGracePeriod(thrLocalConfig.getSocketTimeOut())
			.setSoKeepAlive(thrLocalConfig.getSocketKeepAliveFlag())
			.setSoLinger(thrLocalConfig.getSocketLinger())
			.setSoReuseAddress(thrLocalConfig.getSocketReuseAddrFlag())
			.setSoTimeout(thrLocalConfig.getSocketTimeOut())
			.setTcpNoDelay(thrLocalConfig.getSocketTCPNoDelayFlag())
			.setRcvBufSize(Constants.BUFF_SIZE_LO)
			.setSndBufSize(Constants.BUFF_SIZE_LO)
			.setConnectTimeout(
				timeOutMs > 0 && timeOutMs < Integer.MAX_VALUE ? (int) timeOutMs : Integer.MAX_VALUE
			);
		//
		final NHttpClientEventHandler reqExecutor = new HttpAsyncRequestExecutor();
		//
		final ConnectionConfig connConfig = ConnectionConfig
			.custom()
			.setBufferSize(Constants.BUFF_SIZE_LO)
			.setFragmentSizeHint(0)
			.build();
		final IOEventDispatch ioEventDispatch = new DefaultHttpClientIODispatch(
			reqExecutor, connConfig
		);
		//
		final IOWorker.Factory ioWorkerFactory = new IOWorker.Factory(getName());
		try {
			ioReactor = new DefaultConnectingIOReactor(
				ioReactorConfigBuilder.build(), ioWorkerFactory
			);
		} catch(final IOReactorException e) {
			throw new IllegalStateException("Failed to build the I/O reactor", e);
		}
		//
		final NIOConnFactory<HttpHost, NHttpClientConnection>
			connFactory = new BasicNIOConnFactory(
			null, null, null, null,
			DirectByteBufferAllocator.INSTANCE, connConfig
		);
		//
		//
		connPoolMap = new HashMap<>(storageNodeCount);
		HttpHost nextRoute;
		HttpConnPool<HttpHost, BasicNIOPoolEntry> nextConnPool;
		for(int i = 0; i < storageNodeCount; i ++) {
			nextRoute = wsReqConfigCopy.getNodeHost(addrs[i]);
			nextConnPool = new FixedRouteSequencingConnPool(
				ioReactor, nextRoute, connFactory,
				timeOutMs > 0 && timeOutMs < Integer.MAX_VALUE ?
					(int) timeOutMs : Integer.MAX_VALUE,
				batchSize
			);
			nextConnPool.setDefaultMaxPerRoute(connCountPerNode);
			nextConnPool.setMaxTotal(connCountPerNode);
			connPoolMap.put(nextRoute, nextConnPool);
		}
		//
		mgmtTasks.add(new HttpClientRunTask(ioEventDispatch, ioReactor));
	}
	//
	@Override
	protected WSContainerIOTask<T, C> getIOTask(final C item, final String nextNodeAddr) {
		return new BasicWSContainerTask<>(item, nextNodeAddr, reqConfigCopy);
	}
	//
	@Override
	protected void interruptActually() {
		try {
			super.interruptActually();
		} finally {
			for(final HttpConnPool<HttpHost, BasicNIOPoolEntry> nextConnPool : connPoolMap.values()) {
				nextConnPool.closeExpired();
				LOG.debug(
					Markers.MSG, "{}: closed expired (if any) connections in the pool", getName()
				);
				try {
					nextConnPool.closeIdle(1, TimeUnit.MILLISECONDS);
					LOG.debug(
						Markers.MSG, "{}: closed idle connections (if any) in the pool", getName()
					);
				} finally {
					try {
						nextConnPool.shutdown(1);
						LOG.debug(Markers.MSG, "{}: connection pool has been shut down", getName());
					} catch(final IOException e) {
						LogUtil.exception(
							LOG, Level.WARN, e, "{}: connection pool shutdown failure", getName()
						);
					}
				}
			}
			//
			try {
				ioReactor.shutdown(1);
				LOG.debug(Markers.MSG, "{}: I/O reactor has been shut down", getName());
			} catch(final IOException e) {
				LogUtil.exception(
					LOG, Level.WARN, e, "{}: failed to shut down the I/O reactor", getName()
				);
			}
		}
	}
	//
	@Override
	protected Future<? extends WSContainerIOTask<T, C>> submitReqActually(final IOTask<C> ioTask)
	throws RejectedExecutionException {
		//
		final WSContainerIOTask<T, C> wsTask = (WSContainerIOTask<T, C>) ioTask;
		final HttpConnPool<HttpHost, BasicNIOPoolEntry>
			connPool = connPoolMap.get(wsTask.getTarget());
		if(connPool.isShutdown()) {
			throw new RejectedExecutionException("Connection pool is shut down");
		}
		//
		final Future<WSContainerIOTask<T, C>> futureResult;
		try {
			futureResult = client.execute(wsTask, wsTask, connPool, wsTask, futureCallback);
			if(LOG.isTraceEnabled(Markers.MSG)) {
				LOG.trace(
					Markers.MSG, "I/O task #{} has been submitted for execution", wsTask.hashCode()
				);
			}
		} catch(final Exception e) {
			throw new RejectedExecutionException(e);
		}
		return futureResult;
	}
	//
	private final FutureCallback<WSContainerIOTask<T, C>> futureCallback = new FutureCallback<WSContainerIOTask<T, C>>() {
		@Override
		public final void completed(final WSContainerIOTask<T, C> ioTask) {
			ioTaskCompleted(ioTask);
		}
		//
		public final void cancelled() {
			ioTaskCancelled(1);
		}
		//
		public final void failed(final Exception e) {
			ioTaskFailed(1, e);
		}
	};
	//
	@Override
	public int submitReqs(final List<? extends IOTask<C>> ioTasks, final int from, final int to)
	throws RemoteException, RejectedExecutionException {
		int n = 0;
		if(isPipeliningEnabled) {
			if(ioTasks.size() > 0) {
				final List<WSContainerIOTask<T, C>> wsIOTasks = (List<WSContainerIOTask<T, C>>) ioTasks;
				final WSContainerIOTask<T, C> anyTask = wsIOTasks.get(0);
				final HttpHost tgtHost = anyTask.getTarget();
				if(
					null == client.executePipelined(
						tgtHost, wsIOTasks, wsIOTasks, connPoolMap.get(tgtHost),
						HttpCoreContext.create(), new BatchFutureCallback(wsIOTasks)
					)
					) {
					return 0;
				}
			}
		} else {
			for(int i = from; i < to; i ++) {
				if(null != submitReq(ioTasks.get(i))) {
					n ++;
				} else {
					break;
				}
			}
		}
		return n;
	}
	//
	private final class BatchFutureCallback
	implements FutureCallback<List<WSContainerIOTask<T, C>>> {
		//
		private final List<WSContainerIOTask<T, C>> tasks;
		//
		private BatchFutureCallback(final List<WSContainerIOTask<T, C>> tasks) {
			this.tasks = tasks;
		}
		//
		@Override
		public final void completed(final List<WSContainerIOTask<T, C>> result) {
			ioTaskCompletedBatch(result, 0, result.size());
		}
		//
		@Override
		public final void failed(final Exception e) {
			ioTaskFailed(tasks.size(), e);
		}
		//
		@Override
		public final void cancelled() {
			ioTaskCancelled(tasks.size());
		}
	}
}
