package com.emc.mongoose.storage.mock.impl.base;

import com.emc.mongoose.common.collection.ListingLRUMap;
import com.emc.mongoose.common.concurrent.DaemonBase;
import com.emc.mongoose.model.api.data.ContentSource;
import com.emc.mongoose.model.api.item.ItemFactory;
import com.emc.mongoose.model.impl.item.CsvFileItemInput;
import com.emc.mongoose.storage.mock.api.MutableDataItemMock;
import com.emc.mongoose.storage.mock.api.ObjectContainerMock;
import com.emc.mongoose.storage.mock.api.StorageIoStats;
import com.emc.mongoose.storage.mock.api.StorageMock;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockException;
import com.emc.mongoose.storage.mock.api.exception.ContainerMockNotFoundException;
import com.emc.mongoose.storage.mock.api.exception.ObjectMockNotFoundException;
import com.emc.mongoose.storage.mock.api.exception.StorageMockCapacityLimitReachedException;
import static com.emc.mongoose.ui.config.Config.ItemConfig;
import static com.emc.mongoose.ui.config.Config.LoadConfig.MetricsConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig.MockConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig.MockConfig.ContainerConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Markers;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 Created on 19.07.16.
 */
public abstract class StorageMockBase<I extends MutableDataItemMock>
extends DaemonBase
implements StorageMock<I> {

	private static final Logger LOG = LogManager.getLogger();

	private final String itemInputFile;
	private final StorageIoStats ioStats;
	protected final ContentSource contentSrc;
	private final int storageCapacity, containerCapacity;

	private final ListingLRUMap<String, ObjectContainerMock<I>> storageMap;
	private final ObjectContainerMock<I> defaultContainer;

	private volatile boolean isCapacityExhausted = false;

	@SuppressWarnings("unchecked")
	public StorageMockBase(
		final MockConfig mockConfig, final MetricsConfig metricsConfig, final ItemConfig itemConfig,
		final ContentSource contentSrc
	) {
		super();
		final ContainerConfig containerConfig = mockConfig.getContainerConfig();
		storageMap = new ListingLRUMap<>(containerConfig.getCountLimit());
		this.itemInputFile = itemConfig.getInputConfig().getFile();
		this.contentSrc = contentSrc;
		this.ioStats = new BasicStorageIoStats(this, (int) metricsConfig.getPeriod());
		this.storageCapacity = mockConfig.getCapacity();
		this.containerCapacity = containerConfig.getCapacity();
		this.defaultContainer = new BasicObjectContainerMock<>(containerCapacity);
		storageMap.put(DEFAULT_CONTAINER_NAME, defaultContainer);
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	// Container methods
	////////////////////////////////////////////////////////////////////////////////////////////////

	@Override
	public final void createContainer(final String name) {
		synchronized(storageMap) {
			storageMap.put(name, new BasicObjectContainerMock<>(containerCapacity));
		}
		ioStats.containerCreate();
	}

	@Override
	public final ObjectContainerMock<I> getContainer(final String name) {
		synchronized(storageMap) {
			return storageMap.get(name);
		}
	}

	@Override
	public final void deleteContainer(final String name) {
		synchronized(storageMap) {
			storageMap.remove(name);
		}
		ioStats.containerDelete();
	}

	////////////////////////////////////////////////////////////////////////////////////////////////
	// Object methods
	////////////////////////////////////////////////////////////////////////////////////////////////

	protected abstract I newDataObject(final String id, final long offset, final long size);

	@Override
	public final void createObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockNotFoundException, StorageMockCapacityLimitReachedException {
		if(isCapacityExhausted) {
			throw new StorageMockCapacityLimitReachedException();
		}
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			c.put(id, newDataObject(id, offset, size));
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final void updateObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockException, ObjectMockNotFoundException {
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			final I obj = c.get(id);
			if(obj != null) {
				obj.update(offset, size);
			} else {
				throw new ObjectMockNotFoundException(id);
			}
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}
	//
	@Override
	public final void appendObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockException, ObjectMockNotFoundException {
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			final I obj = c.get(id);
			if(obj != null) {
				obj.append(offset, size);
			} else {
				throw new ObjectMockNotFoundException(id);
			}
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final I getObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockException {
		// TODO partial read using offset and size args
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			return c.get(id);
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final void deleteObject(
		final String containerName, final String id, final long offset, final long size
	) throws ContainerMockNotFoundException {
		final ObjectContainerMock<I> c = getContainer(containerName);
		if(c != null) {
			c.remove(id);
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	@Override
	public final I listObjects(
		final String containerName, final String afterObjectId, final Collection<I> outputBuffer,
		final int limit
	) throws ContainerMockException {
		final ObjectContainerMock<I> container = getContainer(containerName);
		if(container != null) {
			return container.list(afterObjectId, outputBuffer, limit);
		} else {
			throw new ContainerMockNotFoundException(containerName);
		}
	}

	private final Thread storageCapacityMonitorThread = new Thread("storageMockCapacityMonitor") {
		{
			setDaemon(true);
		}
		@SuppressWarnings("InfiniteLoopStatement")
		@Override
		public final void run() {
			long currObjCount;
			try {
				while(true) {
					TimeUnit.SECONDS.sleep(1);
					currObjCount = getSize();
					if(!isCapacityExhausted && currObjCount > storageCapacity) {
						isCapacityExhausted = true;
					} else if(isCapacityExhausted && currObjCount <= storageCapacity) {
						isCapacityExhausted = false;
					}
				}
			} catch(final InterruptedException ignored) {
			}
		}
	};

	@Override
	protected void doStart() {
		loadPersistedDataItems();
		ioStats.start();
		storageCapacityMonitorThread.start();
	}
	
	@Override
	protected void doShutdown()
	throws IllegalStateException {
	}
	
	@Override
	protected void doInterrupt()
	throws IllegalStateException {
		storageCapacityMonitorThread.interrupt();
	}
	
	@Override
	public long getSize() {
		long size = 0;
		synchronized(storageMap) {
			for(final ObjectContainerMock<I> container : storageMap.values()) {
				size += container.size();
			}
		}
		return size;
	}

	@Override
	public long getCapacity() {
		return storageCapacity;
	}

	@Override
	public final void putIntoDefaultContainer(final List<I> dataItems) {
		for(final I object : dataItems) {
			defaultContainer.put(object.getName(), object);
		}
	}

	@Override
	public StorageIoStats getStats() {
		return ioStats;
	}

	@SuppressWarnings({"InfiniteLoopStatement", "unchecked"})
	private void loadPersistedDataItems() {
		if(itemInputFile != null && !itemInputFile.isEmpty()) {
			final Path itemInputFile = Paths.get(this.itemInputFile);
			if(!Files.exists(itemInputFile)) {
				LOG.warn(Markers.ERR, "Item input file @ \"{}\" doesn't exists", itemInputFile);
				return;
			}
			if(Files.isDirectory(itemInputFile)) {
				LOG.warn(Markers.ERR, "Item input file @ \"{}\" is a directory", itemInputFile);
				return;
			}
			
			final AtomicLong count = new AtomicLong(0);
			List<I> buff;
			int n;
			final Thread displayProgressThread = new Thread(
				() -> {
					try {
						while(true) {
							LOG.info(Markers.MSG, "{} items loaded...", count.get());
							TimeUnit.SECONDS.sleep(10);
						}
					} catch(final InterruptedException e) {
					}
				}
			);
			
			final ItemFactory<I> itemFactory = new BasicMutableDataItemMockFactory<>(contentSrc);
			try(
				final CsvFileItemInput<I> csvFileItemInput = new CsvFileItemInput<>(
					itemInputFile, itemFactory
				)
			) {
				displayProgressThread.start();
				do {
					buff = new ArrayList<>(4096);
					n = csvFileItemInput.get(buff, 4096);
					if(n > 0) {
						putIntoDefaultContainer(buff);
						count.addAndGet(n);
					} else {
						break;
					}
				} while(true);
			} catch(final EOFException e) {
				LOG.info(Markers.MSG, "Loaded {} data items from file {}", count, itemInputFile);
			} catch(final IOException | NoSuchMethodException e) {
				LogUtil.exception(
					LOG, Level.WARN, e, "Failed to load the data items from file \"{}\"",
					itemInputFile
				);
			} finally {
				displayProgressThread.interrupt();
			}
		}
	}
	
	@Override
	protected void doClose()
	throws IOException {
		ioStats.close();
		contentSrc.close();
		try {
			storageMap.clear();
		} catch(final ConcurrentModificationException e) {
			LogUtil.exception(LOG, Level.DEBUG, e, "Failed to clean up the storage mock");
		}
		try {
			for(final ObjectContainerMock<I> containerMock : storageMap.values()) {
				containerMock.close();
			}
		} catch(final ConcurrentModificationException e) {
			LogUtil.exception(LOG, Level.DEBUG, e, "Failed to clean up the containers");
		}
	}
}