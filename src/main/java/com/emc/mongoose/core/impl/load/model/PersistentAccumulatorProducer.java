package com.emc.mongoose.core.impl.load.model;
//
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
//
import com.emc.mongoose.core.api.data.DataItem;
import com.emc.mongoose.core.api.data.model.DataItemOutput;
import com.emc.mongoose.core.api.load.model.AccumulatorProducer;
import com.emc.mongoose.core.api.load.model.Consumer;
import com.emc.mongoose.core.api.load.model.Producer;
//
//
//
import com.emc.mongoose.core.impl.data.model.CSVFileItemOutput;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 16.06.15.
 */
public class PersistentAccumulatorProducer<T extends DataItem>
extends AsyncConsumerBase<T>
implements AccumulatorProducer<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final DataItemOutput<T> tmpFileOutput;
	private final File tmpFile;
	private final Producer<T> tmpFileProducer;
	//
	private volatile long count = 0;
	//
	protected final Class<T> dataCls;
	//
	public PersistentAccumulatorProducer(
		final Class<T> itemCls, final RunTimeConfig rtConfig, final long maxCount
	) {
		super(
			maxCount, rtConfig.getLoadLimitTimeUnit().toMillis(rtConfig.getLoadLimitTimeValue()),
			rtConfig.getTasksMaxQueueSize()
		);
		//
		this.dataCls = itemCls;
		final Path tmpFilePath = Paths.get(
			System.getProperty("java.io.tmpdir"),
			rtConfig.getRunName() + "-v" + rtConfig.getRunVersion()
		);
		if(!tmpFilePath.toFile().exists() && !tmpFilePath.toFile().mkdirs()) {
			LOG.warn(Markers.ERR, "Failed to create the directory: \"{}\"", tmpFilePath);
		}
		//
		try {
			tmpFile = Files.createTempFile(tmpFilePath, rtConfig.getRunId(), null).toFile();
			tmpFile.deleteOnExit();
		} catch(final IOException e) {
			throw new IllegalStateException(
				"Failed to create the temporary file in " + tmpFilePath.toAbsolutePath(), e
			);
		}
		//
		try {
			/*tmpFileWriter = new BufferedWriter(
				new OutputStreamWriter(Files.newOutputStream(tmpFile.toPath()))
			);*/
			tmpFileOutput = new CSVFileItemOutput<T>(tmpFile.toPath(), itemCls);
		} catch(final IOException e) {
			throw new IllegalStateException(
				"Failed to open the temporary file in " + tmpFilePath.toAbsolutePath(),
				e
			);
		}
		//
		try {
			tmpFileProducer = new DataItemInputProducer<>(tmpFileOutput.getInput());
		} catch(final IOException e) {
			throw new IllegalStateException(e);
		}
		//
		setName("consumer<" + tmpFile.getName() + ">");
		super.start();
	}
	//
	@Override
	protected void submitSync(final T item)
	throws InterruptedException, RemoteException {
		if(item != null) {
			try {
				synchronized(tmpFileOutput) {
					tmpFileOutput.write(item);
				}
				count ++;
			} catch(final IOException e) {
				throw new RejectedExecutionException(e);
			}
		}
	}
	//
	@Override
	public void interrupt() {
		// the synchronization is necessary here to make sure that every data item is
		// written completely to the file
		synchronized(tmpFileOutput) {
			super.interrupt();
		}
		//
		try {
			close();
		} catch(final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "Unexpected failure");
		}
		//
		if(tmpFile.delete()) {
			LOG.debug(
				Markers.MSG, "{}: temporary file \"{}\" deleted", getName(),
				tmpFile.getAbsolutePath()
			);
		}
	}
	//
	@Override
	public void close()
	throws IOException {
		try {
			super.close();
		} finally {
			synchronized(tmpFileOutput) {
				tmpFileOutput.close();
			}
			LOG.debug(
				Markers.MSG, "{}: closed the file \"{}\" for writing", getName(),
				tmpFile.getAbsolutePath()
			);
		}
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Producer implementation /////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public void start() {
		try {
			tmpFileProducer.start();
		} catch(final RemoteException ignored) {
		}
	}
	//
	@Override
	public void setConsumer(final Consumer<T> consumer)
	throws RemoteException {
		tmpFileProducer.setConsumer(consumer);
	}
	//
	@Override
	public Consumer<T> getConsumer()
	throws RemoteException {
		return tmpFileProducer.getConsumer();
	}
	//
	@Override
	public void await()
	throws RemoteException, InterruptedException {
		tmpFileProducer.await();
	}
	//
	@Override
	public void await(final long timeOut, final TimeUnit timeUnit)
	throws RemoteException, InterruptedException {
		tmpFileProducer.await(timeOut, timeUnit);
	}
	//
	@Override
	public final long getCount() {
		return count;
	}
}
