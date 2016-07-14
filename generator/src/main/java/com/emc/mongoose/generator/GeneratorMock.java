package com.emc.mongoose.generator;

import com.emc.mongoose.common.concurrent.LifeCycle;
import com.emc.mongoose.common.concurrent.LifeCycleBase;
import com.emc.mongoose.common.concurrent.Throttle;
import com.emc.mongoose.common.config.ItemNamingType;
import com.emc.mongoose.common.config.LoadType;
import com.emc.mongoose.common.data.SeedContentSource;
import com.emc.mongoose.common.io.Input;
import com.emc.mongoose.common.io.IoTask;
import com.emc.mongoose.common.io.Output;
import com.emc.mongoose.common.io.factory.IoTaskFactory;
import com.emc.mongoose.common.io.value.RangePatternDefinedInput;
import com.emc.mongoose.common.item.BasicDataItem;
import com.emc.mongoose.common.item.BasicItemNameInput;
import com.emc.mongoose.common.item.DataItem;
import com.emc.mongoose.common.item.Item;
import com.emc.mongoose.common.item.NewDataItemInput;
import com.emc.mongoose.common.item.factory.BasicDataItemFactory;
import com.emc.mongoose.common.item.factory.ItemFactory;
import com.emc.mongoose.common.load.Driver;
import com.emc.mongoose.common.load.Generator;
import com.emc.mongoose.common.load.Monitor;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.util.SizeInBytes;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 Created by kurila on 11.07.16.
 */
public class GeneratorMock<I extends Item, O extends IoTask<I>>
extends LifeCycleBase
implements Generator<I, O> {

	private final static Logger LOG = LogManager.getLogger();

	private final List<Driver<I, O>> drivers;
	private final AtomicReference<Monitor<I, O>> monitorRef = new AtomicReference<>(null);
	private final LoadType ioType; // job.type
	private final Input<I> itemInput;
	private final Output<I> itemOutput;
	private final Thread worker;
	private final int batchSize = 0x1000;
	private final int maxItemQueueSize = 0x100000;
	private final boolean isShuffling = false;
	private final boolean isCircular = false;
	private final Throttle<I> rateThrottle = new RateThrottle<>(0);
	private final IoTaskFactory<I, O> ioTaskFactory;

	private long producedItemsCount = 0;

	private final class GeneratorTask
	implements Runnable {

		private final Random rnd = new Random();

		@Override
		public final void run() {
			if(itemInput == null) {
				LOG.debug(Markers.MSG, "No item source for the producing, exiting");
				return;
			}
			int n = 0, m = 0;
			try {
				List<I> buff;
				while(!isInterrupted()) {
					try {
						buff = new ArrayList<>(batchSize);
						n = itemInput.get(buff, batchSize);
						if(isShuffling) {
							Collections.shuffle(buff, rnd);
						}
						if(isInterrupted()) {
							break;
						}
						if(n > 0 && rateThrottle.requestContinueFor(null, n)) {
							for(m = 0; m < n && !isInterrupted(); ) {
								m += itemOutput.put(buff, m, n);
								Thread.yield();
							}
							producedItemsCount += n;
						} else {
							if(isInterrupted()) {
								break;
							}
						}
						// CIRCULARITY FEATURE:
						// produce only <maxItemQueueSize> items in order to make it possible to enqueue
						// them infinitely
						if(isCircular && producedItemsCount >= maxItemQueueSize) {
							break;
						}
					} catch(
						final EOFException | InterruptedException | ClosedByInterruptException |
							IllegalStateException e
						) {
						break;
					} catch(final IOException e) {
						LogUtil.exception(
							LOG, Level.DEBUG, e, "Failed to transfer the data items, count = {}, " +
								"batch size = {}, batch offset = {}", producedItemsCount, n, m
						);
					}
				}
			} finally {
				LOG.debug(
					Markers.MSG, "{}: produced {} items from \"{}\" for the \"{}\"",
					Thread.currentThread().getName(), producedItemsCount, itemInput, itemOutput
				);
				try {
					itemInput.close();
				} catch(final IOException e) {
					LogUtil.exception(
						LOG, Level.WARN, e, "Failed to close the item source \"{}\"", itemInput
					);
				}
			}
		}
	}

	private final class IoTaskSubmitOutput
	implements Output<I> {

		@Override
		public void put(final I item)
		throws IOException {
			final O nextIoTask = ioTaskFactory.getInstance(ioType, item);
			final Driver<I, O> nextDriver = getNextDriver();
			nextDriver.submit(nextIoTask);
		}

		@Override
		public int put(final List<I> buffer, final int from, final int to)
		throws IOException {
			if(to > from) {
				final List<O> ioTasks = new ArrayList<>(to - from);
				for(int i = from; i < to; i ++) {
					ioTasks.add(ioTaskFactory.getInstance(ioType, buffer.get(i)));
				}
				final Driver<I, O> nextDriver = getNextDriver();
				nextDriver.submit(ioTasks, 0, ioTasks.size());
			}
			return to - from;
		}

		@Override
		public int put(final List<I> buffer)
		throws IOException {
			final int n = buffer.size();
			final List<O> ioTasks = new ArrayList<>(n);
			for(final I nextItem : buffer) {
				ioTasks.add(ioTaskFactory.getInstance(ioType, nextItem));
			}
			final Driver<I, O> nextDriver = getNextDriver();
			nextDriver.submit(ioTasks, 0, n);
			return n;
		}

		@Override
		public Input<I> getInput()
		throws IOException {
			return itemInput;
		}

		@Override
		public void close()
		throws IOException {
		}
	}

	public GeneratorMock(
		final List<Driver<I, O>> drivers, final LoadType ioType,
		final ItemFactory<I> itemFactory, final IoTaskFactory<I, O> ioTaskFactory
	) throws IllegalStateException, IllegalArgumentException {
		this.drivers = drivers;
		if(itemFactory instanceof BasicDataItemFactory) {
			this.itemInput = new NewDataItemInput<>(
				(ItemFactory) itemFactory,
				new RangePatternDefinedInput(Item.SLASH),
				new BasicItemNameInput(ItemNamingType.RANDOM, null, 13, 36, 0),
				new SeedContentSource(
					Long.valueOf("7a42d9c483244167", 16), SizeInBytes.toFixedSize("4MB")
				),
				new SizeInBytes("1MB")
			);
		} else {
			// TODO
			this.itemInput = null;
		}
		this.ioType = ioType;
		this.ioTaskFactory = ioTaskFactory;
		this.itemOutput = new IoTaskSubmitOutput();
		worker = new Thread(new GeneratorTask(), "generator");
		worker.setDaemon(true);
	}

	@Override
	public final void registerMonitor(final Monitor<I, O> monitor)
	throws IllegalStateException {
		if(monitorRef.compareAndSet(null, monitor)) {
			for(final Driver<I, O> driver : drivers) {
				driver.registerMonitor(monitor);
			}
		} else {
			throw new IllegalStateException("Generator is already used by another monitor");
		}
	}

	private final AtomicLong rrc = new AtomicLong(0);
	protected Driver<I, O> getNextDriver() {
		return drivers.get((int) (rrc.incrementAndGet() % drivers.size()));
	}

	@Override
	protected void doStart() {
		drivers.forEach(LifeCycle::start);
		worker.start();
	}

	@Override
	protected void doShutdown() {
		doInterrupt();
	}

	@Override
	protected void doInterrupt() {
		worker.interrupt();
		drivers.forEach(LifeCycle::shutdown);
	}

	@Override
	public boolean await()
	throws InterruptedException {
		worker.join();
		return true;
	}

	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException {
		timeUnit.timedJoin(worker, timeout);
		return true;
	}

	@Override
	public void close()
	throws IOException {
		if(!isInterrupted()) {
			interrupt();
		}
		if(itemInput != null) {
			itemInput.close();
		}
		if(itemOutput != null) {
			itemOutput.close();
		}
		for(final Driver<I, O> nextDriver : drivers) {
			nextDriver.close();
		}
	}
}
