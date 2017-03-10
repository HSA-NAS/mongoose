package com.emc.mongoose.run.scenario.job;

import com.emc.mongoose.common.exception.UserShootHisFootException;
import com.emc.mongoose.common.net.ServiceUtil;
import com.emc.mongoose.load.monitor.BasicLoadMonitor;
import com.emc.mongoose.model.data.ContentSource;
import com.emc.mongoose.model.data.ContentSourceUtil;
import com.emc.mongoose.common.io.Output;
import com.emc.mongoose.model.item.ItemFactory;
import com.emc.mongoose.model.item.ItemInfoFileOutput;
import com.emc.mongoose.model.item.ItemType;
import com.emc.mongoose.model.load.LoadGenerator;
import com.emc.mongoose.model.load.LoadMonitor;
import com.emc.mongoose.model.storage.StorageDriver;
import com.emc.mongoose.model.storage.StorageDriverSvc;
import com.emc.mongoose.load.generator.BasicLoadGeneratorBuilder;
import com.emc.mongoose.run.scenario.util.StorageDriverUtil;
import com.emc.mongoose.storage.driver.builder.BasicStorageDriverBuilder;
import com.emc.mongoose.storage.driver.builder.StorageDriverBuilderSvc;
import com.emc.mongoose.ui.config.Config;
import static com.emc.mongoose.ui.config.Config.ItemConfig;
import static com.emc.mongoose.ui.config.Config.ItemConfig.DataConfig;
import static com.emc.mongoose.ui.config.Config.ItemConfig.DataConfig.ContentConfig;
import static com.emc.mongoose.ui.config.Config.LoadConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig.DriverConfig;
import static com.emc.mongoose.ui.config.Config.TestConfig.StepConfig;
import static com.emc.mongoose.ui.config.Config.TestConfig.StepConfig.LimitConfig;
import static com.emc.mongoose.ui.config.Config.TestConfig.StepConfig.MetricsConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Markers;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 Created by kurila on 02.02.16.
 */
public final class LoadJob
extends JobBase {
	
	private static final Logger LOG = LogManager.getLogger();
	
	private final boolean preconditionFlag;

	public LoadJob(final Config config) {
		this(config, Collections.EMPTY_MAP, false);
	}
	
	public LoadJob(
		final Config appConfig, final Map<String, Object> subTree, final boolean preconditionFlag
	) {
		super(appConfig);
		final Map<String, Object> nodeConfig = (Map<String, Object>) subTree.get(KEY_NODE_CONFIG);
		if(nodeConfig != null) {
			localConfig.apply(nodeConfig);
		}
		this.preconditionFlag = preconditionFlag;
	}
	
	@Override
	public final void run() {
		super.run();

		final StepConfig stepConfig = localConfig.getTestConfig().getStepConfig();
		final String jobName = stepConfig.getName();
		LOG.info(Markers.MSG, "Run the load job \"{}\"", jobName);
		stepConfig.setPrecondition(preconditionFlag);

		final LoadConfig loadConfig = localConfig.getLoadConfig();
		final LimitConfig limitConfig = stepConfig.getLimitConfig();
		final ItemConfig itemConfig = localConfig.getItemConfig();
		final DataConfig dataConfig = itemConfig.getDataConfig();
		final ContentConfig contentConfig = dataConfig.getContentConfig();
		final StorageConfig storageConfig = localConfig.getStorageConfig();
		
		final ContentSource contentSrc;
		try {
			contentSrc = ContentSourceUtil.getInstance(
				contentConfig.getFile(), contentConfig.getSeed(), contentConfig.getRingSize()
			);
		} catch(final IOException e) {
			throw new RuntimeException(e);
		}
		
		final List<StorageDriver> drivers = new ArrayList<>();
		StorageDriverUtil.init(
			drivers, itemConfig, loadConfig, storageConfig, stepConfig, contentSrc
		);

		final ItemType itemType = ItemType.valueOf(itemConfig.getType().toUpperCase());
		final ItemFactory itemFactory = ItemType.getItemFactory(itemType, contentSrc);
		LOG.info(Markers.MSG, "Work on the " + itemType.toString().toLowerCase() + " items");

		final LoadGenerator loadGenerator;
		try {
			loadGenerator = new BasicLoadGeneratorBuilder<>()
				.setItemConfig(itemConfig)
				.setLoadConfig(loadConfig)
				.setLimitConfig(limitConfig)
				.setItemType(itemType)
				.setItemFactory(itemFactory)
				.setStorageDrivers(drivers)
				.setAuthConfig(storageConfig.getAuthConfig())
				.build();
		} catch(final UserShootHisFootException e) {
			throw new RuntimeException(e);
		}
		LOG.info(Markers.MSG, "Load generators initialized");

		final long timeLimitSec;
		long t = limitConfig.getTime();
		if(t > 0) {
			timeLimitSec = t;
		} else {
			timeLimitSec = Long.MAX_VALUE;
		}

		try(
			final LoadMonitor monitor = new BasicLoadMonitor(
				jobName, loadGenerator, drivers, loadConfig, stepConfig
			)
		) {
			final String itemOutputFile = itemConfig.getOutputConfig().getFile();
			if(itemOutputFile != null && itemOutputFile.length() > 0) {
				final Path itemOutputPath = Paths.get(itemOutputFile);
				if(Files.exists(itemOutputPath)) {
					LOG.warn(
						Markers.ERR, "Items output file \"{}\" already exists", itemOutputPath
					);
				}
				final Output itemOutput = new ItemInfoFileOutput<>(itemOutputPath);
				monitor.setIoResultsOutput(itemOutput);
			}
			monitor.start();
			if(monitor.await(timeLimitSec, TimeUnit.SECONDS)) {
				LOG.info(Markers.MSG, "Load job \"{}\" done", jobName);
			} else {
				LOG.info(Markers.MSG, "Load job \"{}\" timeout", jobName);
			}
		} catch(final RemoteException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Unexpected failure");
		} catch(final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "Failed to open the item output file");
		} catch(final InterruptedException e) {
			LOG.debug(Markers.MSG, "Load job \"{}\" interrupted", jobName);
		}
	}
	
	@Override
	public final String toString() {
		return "singleLoadJobContainer#" + hashCode();
	}
	
	@Override
	public final void close()
	throws IOException {
	}
}