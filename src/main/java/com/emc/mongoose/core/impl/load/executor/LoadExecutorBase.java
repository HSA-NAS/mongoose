package com.emc.mongoose.core.impl.load.executor;
//
import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
//
import com.emc.mongoose.common.collections.InstancePool;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.logging.LogUtil;
import com.emc.mongoose.common.net.ServiceUtils;
//
import com.emc.mongoose.core.api.io.task.IOTask;
import com.emc.mongoose.core.api.io.req.conf.RequestConfig;
import com.emc.mongoose.core.api.data.DataItem;
import com.emc.mongoose.core.api.data.src.DataSource;
import com.emc.mongoose.core.api.load.model.Consumer;
import com.emc.mongoose.core.api.load.executor.LoadExecutor;
import com.emc.mongoose.core.api.load.model.Producer;
//
import com.emc.mongoose.core.impl.io.task.BasicIOTask;
import com.emc.mongoose.core.impl.load.executor.util.ConsumerBase;
import com.emc.mongoose.core.impl.load.tasks.LoadCloseHook;
//
import org.apache.commons.lang.StringUtils;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
//
import javax.management.MBeanServer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
/**
 Created by kurila on 15.10.14.
 */
public abstract class LoadExecutorBase<T extends DataItem>
extends ConsumerBase<T>
implements LoadExecutor<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected final int loadNum, connCountPerNode, storageNodeCount;
	protected final String storageNodeAddrs[];
	//
	protected final DataSource dataSrc;
	protected volatile RunTimeConfig runTimeConfig = RunTimeConfig.getContext();
	protected final RequestConfig<T> reqConfigCopy;
	protected final IOTask.Type loadType;
	//
	private final InstancePool<BasicIOTask> ioTaskPool;
	//
	protected volatile Producer<T> producer = null;
	protected volatile Consumer<T> consumer;
	//
	private final long maxCount;
	private final int totalConnCount;
	// METRICS section
	protected final MetricRegistry metrics = new MetricRegistry();
	protected Counter counterSubm, counterRej, counterReqFail;
	protected Meter throughPut, reqBytes;
	protected Histogram respLatency;
	//
	protected final MBeanServer mBeanServer;
	protected final JmxReporter jmxReporter;
	//
	private final Thread metricsDaemon = new Thread() {
		//
		{ setDaemon(true); } // do not block process exit
		//
		@Override
		public final void run() {
			final long metricsUpdatePeriodMilliSec = TimeUnit.SECONDS.toMillis(
				runTimeConfig.getLoadMetricsPeriodSec()
			);
			try {
				if(metricsUpdatePeriodMilliSec > 0) {
					while(!isClosed.get()) {
						logMetrics(LogUtil.PERF_AVG);
						Thread.sleep(metricsUpdatePeriodMilliSec);
					}
				} else {
					Thread.sleep(Long.MAX_VALUE);
				}
			} catch(final InterruptedException e) {
				LOG.debug(LogUtil.MSG, "{}: interrupted", getName());
			}
		}
	};
	// STATES section
	protected final AtomicLong
		durTasksSum = new AtomicLong(0),
		counterRoundRobinSubm = new AtomicLong(0),
		counterResultHandle = new AtomicLong(0);
	private final AtomicBoolean isClosed = new AtomicBoolean(false);
	private final Lock lock = new ReentrantLock();
	private final Condition condProducerDone = lock.newCondition();
	//
	protected LoadExecutorBase(
		final RunTimeConfig runTimeConfig, final RequestConfig<T> reqConfig, final String[] addrs,
		final int connCountPerNode, final String listFile, final long maxCount,
		final long sizeMin, final long sizeMax, final float sizeBias
	) {
		super(runTimeConfig, maxCount);
		//
		loadNum = LAST_INSTANCE_NUM.getAndIncrement();
		storageNodeCount = addrs.length;
		//
		setName(
			Integer.toString(loadNum) + '-' +
				StringUtils.capitalize(reqConfig.getAPI().toLowerCase()) + '-' +
				StringUtils.capitalize(reqConfig.getLoadType().toString().toLowerCase()) +
				(maxCount > 0 ? Long.toString(maxCount) : "") + '-' +
				Integer.toString(connCountPerNode) + 'x' + Integer.toString(storageNodeCount)
		);
		//
		totalConnCount = connCountPerNode * storageNodeCount;
		//
		this.runTimeConfig = runTimeConfig;
		RequestConfig<T> reqConfigClone = null;
		try {
			reqConfigClone = reqConfig.clone();
		} catch(final CloneNotSupportedException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to clone the request config");
		} finally {
			this.reqConfigCopy = reqConfigClone;
		}
		loadType = reqConfig.getLoadType();
		//
		mBeanServer = ServiceUtils.getMBeanServer(runTimeConfig.getRemotePortExport());
		jmxReporter = JmxReporter.forRegistry(metrics)
			//.convertDurationsTo(TimeUnit.MICROSECONDS)
			//.convertRatesTo(TimeUnit.SECONDS)
			.registerWith(mBeanServer)
			.build();
		//
		this.connCountPerNode = connCountPerNode;
		this.maxCount = maxCount > 0 ? maxCount : Long.MAX_VALUE;
		// prepare the nodes array
		storageNodeAddrs = addrs.clone();
		// create and configure the connection manager
		dataSrc = reqConfig.getDataSource();
		//
		if(listFile != null && listFile.length() > 0 && Files.isReadable(Paths.get(listFile))) {
			producer = newFileBasedProducer(maxCount, listFile);
			LOG.debug(LogUtil.MSG, "{} will use file-based producer: {}", getName(), listFile);
		} else if(loadType == IOTask.Type.CREATE) {
			producer = newDataProducer(maxCount, sizeMin, sizeMax, sizeBias);
			LOG.debug(LogUtil.MSG, "{} will use new data items producer", getName());
		} else {
			producer = reqConfig.getAnyDataProducer(maxCount, addrs[0]);
			LOG.debug(LogUtil.MSG, "{} will use {} as data items producer", getName(), producer);
		}
		//
		try {
			ioTaskPool = new InstancePool<>(
				BasicIOTask.class.getConstructor(LoadExecutor.class), this
			);
		} catch(final NoSuchMethodException e) {
			for(final Constructor c : BasicIOTask.class.getConstructors()) {
				LOG.info(LogUtil.ERR, c);
			}
			throw new IllegalStateException(e);
		}
		//
		if(producer != null) {
			try {
				producer.setConsumer(this);
			} catch(final RemoteException e) {
				LogUtil.exception(LOG, Level.WARN, e, "Unexpected failure");
			}
		}
		LoadCloseHook.add(this);
	}
	//
	protected abstract Producer<T> newFileBasedProducer(final long maxCount, final String listFile);
	protected abstract Producer<T> newDataProducer(
		final long maxCount, final long minObjSize, final long maxObjSize, final float objSizeBias
	);
	//
	@Override
	public final String toString() {
		return getName();
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Producer implementation /////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public final void logMetrics(final Marker logMarker) {
		//
		final long
			countReqSucc = throughPut.getCount(),
			countReqFail = counterReqFail.getCount();
		final double
			meanTP = throughPut.getMeanRate(),
			oneMinTP = throughPut.getOneMinuteRate(),
			fiveMinTP = throughPut.getFiveMinuteRate(),
			fifteenMinTP = throughPut.getFifteenMinuteRate(),
			meanBW = reqBytes.getMeanRate(),
			oneMinBW = reqBytes.getOneMinuteRate(),
			fiveMinBW = reqBytes.getFiveMinuteRate(),
			fifteenMinBW = reqBytes.getFifteenMinuteRate();
		final Snapshot respLatencySnapshot = respLatency.getSnapshot();
		//
		final String message = LogUtil.PERF_SUM.equals(logMarker) ?
			String.format(
				LogUtil.LOCALE_DEFAULT, MSG_FMT_SUM_METRICS,
				//
				getName(),
				countReqSucc,
				countReqFail == 0 ?
					Long.toString(countReqFail) :
					(float) countReqSucc / countReqFail > 100 ?
						String.format(LogUtil.INT_YELLOW_OVER_GREEN, countReqFail) :
						String.format(LogUtil.INT_RED_OVER_GREEN, countReqFail),
				//
				(int) respLatencySnapshot.getMean(),
				(int) respLatencySnapshot.getMin(),
				(int) respLatencySnapshot.getMedian(),
				(int) respLatencySnapshot.getMax(),
				//
				meanTP, oneMinTP, fiveMinTP, fifteenMinTP,
				meanBW / MIB, oneMinBW / MIB, fiveMinBW / MIB, fifteenMinBW / MIB
			) :
			String.format(
				LogUtil.LOCALE_DEFAULT, MSG_FMT_METRICS,
				//
				countReqSucc, counterSubm.getCount() - counterResultHandle.get(),
				countReqFail == 0 ?
					Long.toString(countReqFail) :
					(float) countReqSucc / countReqFail > 100 ?
						String.format(LogUtil.INT_YELLOW_OVER_GREEN, countReqFail) :
						String.format(LogUtil.INT_RED_OVER_GREEN, countReqFail),
				//
				(int) respLatencySnapshot.getMean(),
				(int) respLatencySnapshot.getMin(),
				(int) respLatencySnapshot.getMedian(),
				(int) respLatencySnapshot.getMax(),
				//
				meanTP, oneMinTP, fiveMinTP, fifteenMinTP,
				meanBW / MIB, oneMinBW / MIB, fiveMinBW / MIB, fifteenMinBW / MIB
			);
		LOG.info(logMarker, message);
	}
	//
	private final AtomicLong tsStart = new AtomicLong(-1);
	//
	@Override
	public void start() {
		if(tsStart.compareAndSet(-1, System.nanoTime())) {
			LOG.debug(LogUtil.MSG, "Starting {}", getName());
			// init metrics
			counterSubm = metrics.counter(MetricRegistry.name(getName(), METRIC_NAME_SUBM));
			counterRej = metrics.counter(MetricRegistry.name(getName(), METRIC_NAME_REJ));
			counterReqFail = metrics.counter(MetricRegistry.name(getName(), METRIC_NAME_FAIL));
			throughPut = metrics.meter(MetricRegistry.name(getName(), METRIC_NAME_REQ, METRIC_NAME_TP));
			reqBytes = metrics.meter(MetricRegistry.name(getName(), METRIC_NAME_REQ, METRIC_NAME_BW));
			respLatency = metrics.histogram(MetricRegistry.name(getName(), METRIC_NAME_REQ, METRIC_NAME_LAT));
			//
			super.start();
			if(producer == null) {
				LOG.debug(LogUtil.MSG, "{}: using an external data items producer", getName());
			} else {
				//
				try {
					producer.start();
					LOG.debug(LogUtil.MSG, "Started object producer {}", producer);
				} catch(final IOException e) {
					LogUtil.exception(LOG, Level.WARN, e, "Failed to start the producer");
				}
			}
			//
			jmxReporter.start();
			metricsDaemon.setName("metricsDaemon<" + getName() + ">");
			metricsDaemon.start();
			//
			LOG.debug(LogUtil.MSG, "Started \"{}\"", getName());
		} else {
			LOG.warn(LogUtil.ERR, "Second start attempt - skipped");
		}
	}
	//
	@Override
	public final synchronized void interrupt() {
		if(metricsDaemon.isAlive()) {
			metricsDaemon.interrupt();
			shutdown();
			// releasing the blocked join() methods, if any
			try {
				if(lock.tryLock(runTimeConfig.getRunReqTimeOutMilliSec(), TimeUnit.MILLISECONDS)) {
					try {
						condProducerDone.signalAll();
						LOG.debug(LogUtil.MSG, "{}: done/interrupted signal emitted", getName());
					} finally {
						lock.unlock();
					}
				} else {
					LOG.warn(LogUtil.ERR, "{}: failed to acquire the lock in close method", getName());
				}
			} catch(final InterruptedException e) {
				LogUtil.exception(
					LOG, Level.WARN, e, "{}: Interrupted while acquiring the lock", getName()
				);
			}
			//
			final long tsStartNanoSec = tsStart.get();
			if(tsStartNanoSec > 0) { // if was executing
				logMetrics(LogUtil.PERF_SUM); // provide summary metrics
				// calculate the efficiency and report
				final float
					loadDurMicroSec = (float) (System.nanoTime() - tsStart.get()) / 1000,
					eff = durTasksSum.get() / (loadDurMicroSec * totalConnCount);
				LOG.debug(
					LogUtil.MSG,
					String.format(
						LogUtil.LOCALE_DEFAULT,
						"%s: load execution duration: %3.3f[sec], efficiency estimation: %3.3f[%%]",
						getName(), loadDurMicroSec / 1e6, 100 * eff
					)
				);
			} else {
				LOG.debug(LogUtil.ERR, "{}: trying to interrupt while not started", getName());
			}
			//
			LOG.debug(LogUtil.MSG, "{} interrupted", getName());
		} else {
			LOG.debug(LogUtil.MSG, "{} was already interrupted", getName());
		}

	}
	//
	@Override
	public final Consumer<T> getConsumer() {
		return consumer;
	}
	//
	@Override
	public final void setConsumer(final Consumer<T> consumer) {
		this.consumer = consumer;
		LOG.debug(
			LogUtil.MSG, "Appended the consumer \"{}\" for producer \"{}\"", consumer, getName()
		);
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Consumer implementation /////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public void submit(final T dataItem)
	throws InterruptedException, RemoteException, RejectedExecutionException {
		try {
			super.submit(dataItem);
		} catch(final RejectedExecutionException e) {
			counterRej.inc();
			throw e;
		}
	}
	//
	@Override @SuppressWarnings("unchecked")
	protected final void submitSync(final T dataItem) {
		if(counterSubm.getCount() + counterRej.getCount() >= maxCount) {
			LOG.debug(
				LogUtil.MSG, "{}: all tasks has been submitted ({}) or rejected ({})", getName(),
				counterSubm.getCount(), counterRej.getCount()
			);
			super.interrupt();
		}
		// prepare the I/O task instance (make the link between the data item and load type)
		final String nextNodeAddr = getNextNode();
		final IOTask<T> ioTask = getIOTask(dataItem, nextNodeAddr);
		// try to sleep while underlying connection pool becomes more free
		try {
			while(counterSubm.getCount() - counterResultHandle.get() >= maxQueueSize) {
				Thread.sleep(submTimeOutMilliSec);
			}
		} catch(final InterruptedException ignored) {
		}
		//
		try {
			submit(ioTask);
			counterSubm.inc();
		} catch(final Exception e) {
			LogUtil.exception(LOG, Level.DEBUG, e, "Submit failure");
		}
	}
	//
	@SuppressWarnings("unchecked")
	protected IOTask<T> getIOTask(final T dataItem, final String addr) {
		return ioTaskPool.take(dataItem, addr);
	}
	//
	protected void releaseIOTask(final IOTask<T> ioTask) {
		ioTaskPool.release(BasicIOTask.class.cast(ioTask));
	}
	//
	private String getNextNode() {
		return storageNodeAddrs[(int) (counterRoundRobinSubm.getAndIncrement() % storageNodeCount)];
	}
	//
	@Override
	public final void handleResult(final IOTask<T> ioTask)
	throws RemoteException {
		// producing was interrupted?
		if(metricsDaemon.isInterrupted()) {
			return;
		}
		// update the metrics and release the task
		final IOTask.Status status = ioTask.getStatus();
		final T dataItem = ioTask.getDataItem();
		final int latency = ioTask.getLatency();
		if(status == IOTask.Status.SUCC) {
			// update the metrics with success
			throughPut.mark();
			if(latency > 0) {
				respLatency.update(latency);
			}
			durTasksSum.addAndGet(ioTask.getRespTimeDone() - ioTask.getReqTimeStart());
			reqBytes.mark(ioTask.getTransferSize());
			if(LOG.isTraceEnabled(LogUtil.MSG)) {
				LOG.trace(
					LogUtil.MSG, "Task #{}: successful, {}/{}",
					ioTask.hashCode(), throughPut.getCount(), ioTask.getTransferSize()
				);
			}
		}
		// feed the data item to the consumer and finally check for the finish state
		try {
			// is this an end of consumer-producer chain?
			if(consumer == null) {
				LOG.info(LogUtil.DATA_LIST, dataItem);
			} else { // feed to the consumer
				if(LOG.isTraceEnabled(LogUtil.MSG)) {
					LOG.trace(
						LogUtil.MSG, "Going to feed the data item {} to the consumer {}",
						dataItem, consumer
					);
				}
				consumer.submit(dataItem);
				if(LOG.isTraceEnabled(LogUtil.MSG)) {
					LOG.trace(
						LogUtil.MSG, "The data item {} is passed to the consumer {} successfully",
						dataItem, consumer
					);
				}
			}
		} catch(final InterruptedException e) {
			LOG.debug(LogUtil.MSG, "Interrupted");
		} catch(final RemoteException e) {
			LogUtil.exception(
				LOG, Level.WARN, e, "Failed to submit the data item \"{}\" to \"{}\"",
				dataItem, consumer
			);
		} catch(final RejectedExecutionException e) {
			LogUtil.exception(
				LOG, Level.DEBUG, e, "\"{}\" rejected the data item \"{}\"", consumer, dataItem
			);
		} finally {
			//
			releaseIOTask(ioTask);
			//
			final long n = counterResultHandle.incrementAndGet();
			if(n >= maxCount || super.isInterrupted() && n >= counterSubm.getCount()) {
				// max count is reached OR all tasks are done
				LOG.debug(LogUtil.MSG, "{}: all {} task results has been obtained", getName(), n);
				if(!isClosed.get()) {
					try {
						if(
							lock.tryLock(
								runTimeConfig.getRunReqTimeOutMilliSec(), TimeUnit.MILLISECONDS
							)
						) {
							try {
								condProducerDone.signalAll();
								LOG.debug(LogUtil.MSG, "{}: done/interrupted signal emitted", getName());
							} finally {
								lock.unlock();
							}
						} else {
							LOG.debug(LogUtil.ERR, "Failed to acquire the lock for result handling");
						}
					} catch(final InterruptedException e) {
						LogUtil.exception(LOG, Level.DEBUG, e, "Interrupted");
					}
				}
				// prevent further results handling
				interrupt();
			}
		}
	}
	//
	@Override
	public final void shutdown() {
		try {
			if(producer != null) {
				producer.interrupt(); // stop the producing right now
				LOG.debug(
					LogUtil.MSG, "Stopped the producer \"{}\" for \"{}\"", producer, getName()
				);
			}
		} catch(final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "Failed to stop the producer: {}", producer);
		} finally {
			super.shutdown();
		}
	}
	//
	@Override
	public void close()
	throws IOException {
		LOG.debug(LogUtil.MSG, "Invoked close for {}", getName());
		// interrupt the producing
		if(isClosed.compareAndSet(false, true)) {
			try {
				super.close();
				LOG.debug(LogUtil.MSG, "Forcing the shutdown");
				reqConfigCopy.close(); // disables connection drop failures
				if(consumer != null) {
					consumer.shutdown(); // poison the consumer
					LOG.debug(LogUtil.MSG, "Consumer \"{}\" has been poisoned", consumer);
				}
			} catch(final IllegalStateException | RejectedExecutionException e) {
				LogUtil.exception(LOG, Level.DEBUG, e, "Failed to poison the consumer");
			} finally {
				ioTaskPool.clear();
				jmxReporter.close();
				LOG.debug(LogUtil.MSG, "JMX reported closed");
				LoadCloseHook.del(this);
				LOG.debug(LogUtil.MSG, "\"{}\" closed successfully", getName());
			}
		} else {
			LOG.debug(
				LogUtil.MSG,
				"Not closing \"{}\" because it has been closed before already", getName()
			);
		}
	}
	//
	@Override
	protected final void finalize()
	throws Throwable {
		try {
			close();
		} catch(final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "{}: failed to close", getName());
		} finally {
			super.finalize();
		}
	}
	//
	@Override
	public final Producer<T> getProducer() {
		return producer;
	}
	//
	@Override
	public final long getMaxCount() {
		return maxCount;
	}
	//
	@Override
	public final RequestConfig<T> getRequestConfig() {
		return reqConfigCopy;
	}
	//
	@Override
	public final void await()
	throws InterruptedException {
		join(Long.MAX_VALUE);
	}
	//
	@Override
	public final void await(final long timeOut, final TimeUnit timeUnit)
	throws InterruptedException {
		if(super.isInterrupted() || metricsDaemon.isInterrupted() || isClosed.get()) {
			return;
		}
		//
		long t = System.currentTimeMillis(), timeOutMilliSec = timeUnit.toMillis(timeOut);
		if(lock.tryLock(timeOut, timeUnit)) {
			try {
				t = System.currentTimeMillis() - t; // the count of time wasted for locking
				LOG.debug(
					LogUtil.MSG, "{}: wait for the done condition at most for {}[ms]",
					getName(), timeOutMilliSec - t
				);
				if(condProducerDone.await(timeOutMilliSec - t, TimeUnit.MILLISECONDS)) {
					LOG.debug(LogUtil.MSG, "{}: join finished", getName());
				} else {
					LOG.debug(
						LogUtil.MSG, "{}: join timeout, unhandled results left: {}",
						getName(), counterSubm.getCount() - counterResultHandle.get()
					);
				}
			} finally {
				lock.unlock();
			}
		} else {
			LOG.warn(LogUtil.ERR, "Failed to acquire the lock for the join method");
		}
	}
}
