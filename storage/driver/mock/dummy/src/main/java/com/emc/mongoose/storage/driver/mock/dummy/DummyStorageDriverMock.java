package com.emc.mongoose.storage.driver.mock.dummy;

import com.emc.mongoose.api.model.concurrent.DaemonBase;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.io.task.data.DataIoTask;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.api.model.storage.StorageDriver;
import com.emc.mongoose.ui.config.load.LoadConfig;
import com.emc.mongoose.ui.config.storage.StorageConfig;
import com.emc.mongoose.ui.log.Loggers;

import com.github.akurilov.commons.collection.Range;
import com.github.akurilov.commons.io.Input;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 Created by andrey on 11.05.17.
 */
public final class DummyStorageDriverMock<I extends Item, O extends IoTask<I>>
extends DaemonBase
implements StorageDriver<I, O> {

	private final int batchSize;
	private final int outputQueueCapacity;
	private final int concurrencyLevel;
	private final BlockingQueue<O> ioResultsQueue;
	private final LongAdder scheduledTaskCount = new LongAdder();
	private final LongAdder completedTaskCount = new LongAdder();

	public DummyStorageDriverMock(
		final String stepName, final DataInput contentSrc, final LoadConfig loadConfig,
		final StorageConfig storageConfig, final boolean verifyFlag
	) {
		this.batchSize = loadConfig.getBatchConfig().getSize();
		this.outputQueueCapacity = storageConfig.getDriverConfig().getQueueConfig().getOutput();
		this.concurrencyLevel = loadConfig.getLimitConfig().getConcurrency();
		this.ioResultsQueue = new ArrayBlockingQueue<>(outputQueueCapacity);
	}

	@Override
	public final boolean put(final O task)
	throws IOException {
		if(!isStarted()) {
			throw new EOFException();
		}
		checkStateFor(task);
		if(ioResultsQueue.offer(task)) {
			scheduledTaskCount.increment();
			completedTaskCount.increment();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public final int put(final List<O> tasks, final int from, final int to)
	throws IOException {
		if(!isStarted()) {
			throw new EOFException();
		}
		var i = from;
		O nextTask;
		while(i < to && isStarted()) {
			nextTask = tasks.get(i);
			checkStateFor(nextTask);
			if(ioResultsQueue.offer(tasks.get(i))) {
				i ++;
			} else {
				break;
			}
		}
		final var n = i - from;
		scheduledTaskCount.add(n);
		completedTaskCount.add(n);
		return n;
	}

	@Override
	public final int put(final List<O> tasks)
	throws IOException {
		if(!isStarted()) {
			throw new EOFException();
		}
		var n = 0;
		for(final var nextIoTask : tasks) {
			if(isStarted()) {
				checkStateFor(nextIoTask);
				if(ioResultsQueue.offer(nextIoTask)) {
					n ++;
				} else {
					break;
				}
			} else {
				break;
			}
		}
		scheduledTaskCount.add(n);
		completedTaskCount.add(n);
		return n;
	}

	private void checkStateFor(final O ioTask)
	throws IOException {
		ioTask.reset();
		ioTask.startRequest();
		ioTask.finishRequest();
		ioTask.startResponse();
		if(ioTask instanceof DataIoTask) {
			final var dataIoTask = (DataIoTask) ioTask;
			final var dataItem = dataIoTask.item();
			switch(dataIoTask.ioType()) {
				case CREATE:
					dataIoTask.countBytesDone(dataItem.size());
					break;
				case READ:
					dataIoTask.startDataResponse();
				case UPDATE:
					final List<Range> fixedRanges = dataIoTask.fixedRanges();
					if(fixedRanges == null || fixedRanges.isEmpty()) {
						if(dataIoTask.hasMarkedRanges()) {
							dataIoTask.countBytesDone(dataIoTask.markedRangesSize());
						} else {
							dataIoTask.countBytesDone(dataItem.size());
						}
					} else {
						dataIoTask.countBytesDone(dataIoTask.markedRangesSize());
					}
					break;
				default:
					break;
			}
			dataIoTask.startDataResponse();
		}
		ioTask.finishResponse();
		ioTask.status(IoTask.Status.SUCC);
	}

	@Override
	public final Input<O> getInput()
	throws IOException {
		return this;
	}

	@Override
	public final O get()
	throws EOFException, IOException {
		return ioResultsQueue.poll();
	}

	@Override
	public final List<O> getAll() {
		final int n = ioResultsQueue.size();
		final List<O> ioTaskResults = new ArrayList<>(n);
		ioResultsQueue.drainTo(ioTaskResults, n);
		return ioTaskResults;
	}

	@Override
	public final long skip(final long count)
	throws IOException {
		var n = (int) Math.min(count, Integer.MAX_VALUE);
		final var tmpBuff = new ArrayList<O>(n);
		n = ioResultsQueue.drainTo(tmpBuff, n);
		tmpBuff.clear();
		return n;
	}

	@Override
	public final List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws IOException {
		return Collections.emptyList();
	}

	@Override
	public final int getConcurrencyLevel() {
		return concurrencyLevel;
	}

	@Override
	public final int getActiveTaskCount() {
		return (int) (getScheduledTaskCount() - getCompletedTaskCount());
	}

	@Override
	public final long getScheduledTaskCount() {
		return scheduledTaskCount.sum();
	}

	@Override
	public final long getCompletedTaskCount() {
		return completedTaskCount.sum();
	}

	@Override
	public final boolean isIdle() {
		return ioResultsQueue.isEmpty();
	}

	@Override
	public final void adjustIoBuffers(final long avgTransferSize, final IoType ioType) {
	}

	@Override
	protected void doStart()
	throws IllegalStateException {
		Loggers.MSG.debug("{}: started", toString());
	}

	@Override
	protected final void doShutdown()
	throws IllegalStateException {
		Loggers.MSG.debug("{}: shut down", toString());
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException {
		return true;
	}

	@Override
	protected final void doStop()
	throws IllegalStateException {
		Loggers.MSG.debug("{}: interrupted", toString());
	}

	@Override
	protected final void doClose()
	throws IOException {
		ioResultsQueue.clear();
		Loggers.MSG.debug("{}: closed", toString());
	}

	@Override
	public final String toString() {
		return String.format(super.toString(), "mock-dummy");
	}
}
