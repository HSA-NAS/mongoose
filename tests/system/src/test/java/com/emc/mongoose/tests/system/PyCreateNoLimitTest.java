package com.emc.mongoose.tests.system;

import com.emc.mongoose.api.common.env.PathUtil;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.tests.system.base.ScenarioTestBase;
import com.emc.mongoose.tests.system.base.params.Concurrency;
import com.emc.mongoose.tests.system.base.params.DriverCount;
import com.emc.mongoose.tests.system.base.params.ItemSize;
import com.emc.mongoose.tests.system.base.params.StorageType;
import com.emc.mongoose.tests.system.util.DirWithManyFilesDeleter;
import com.emc.mongoose.tests.system.util.OpenFilesCounter;
import com.emc.mongoose.tests.system.util.PortTools;
import org.junit.After;
import org.junit.Before;

import static com.emc.mongoose.api.common.Constants.DIR_EXAMPLE_SCENARIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 Created by andrey on 04.06.17.
 */
public class PyCreateNoLimitTest
extends ScenarioTestBase {

	private String itemOutputPath;
	private String stdOutput;

	public PyCreateNoLimitTest(
		final StorageType storageType, final DriverCount driverCount, final Concurrency concurrency,
		final ItemSize itemSize
	) throws Exception {
		super(storageType, driverCount, concurrency, itemSize);
	}

	@Override
	protected Path makeScenarioPath() {
		return Paths.get(DIR_EXAMPLE_SCENARIO, "py", "default.py");
	}

	@Override
	protected String makeStepId() {
		return PyCreateNoLimitTest.class.getSimpleName() + '-' + storageType.name() + '-' +
			driverCount.name() + 'x' + concurrency.name() + '-' + itemSize.name();
	}

	@Before
	public void setUp()
	throws Exception {
		super.setUp();
		switch(storageType) {
			case FS:
				itemOutputPath = Paths.get(
					Paths.get(PathUtil.getBaseDir()).getParent().toString(), stepId
				).toString();
				configArgs.add("--item-output-path=" + itemOutputPath);
				break;
			case SWIFT:
				configArgs.add("--storage-net-http-namespace=ns1");
				break;
		}
		initTestContainer();
		dockerClient.startContainerCmd(testContainerId).exec();
		TimeUnit.SECONDS.sleep(25);
		stdOutput = stdOutBuff.toString();
	}

	@After
	public void tearDown()
	throws Exception {
		super.tearDown();
		if(storageType.equals(StorageType.FS)) {
			try {
				DirWithManyFilesDeleter.deleteExternal(itemOutputPath);
			} catch(final Exception e) {
				e.printStackTrace(System.err);
			}
		}
	}

	@Override
	public void test()
	throws Exception {

		testMetricsTableStdout(
			stdOutput, stepId, driverCount.getValue(), 0,
			new HashMap<IoType, Integer>() {{ put(IoType.CREATE, concurrency.getValue()); }}
		);

		final int expectedConcurrency = driverCount.getValue() * concurrency.getValue();
		if(StorageType.FS.equals(storageType)) {
			final int actualConcurrency = OpenFilesCounter.getOpenFilesCount(itemOutputPath);
			assertTrue(
				"Expected concurrency <= " + actualConcurrency + ", actual: " + actualConcurrency,
				actualConcurrency <= expectedConcurrency
			);
		} else {
			int actualConcurrency = 0;
			final int startPort = config.getStorageConfig().getNetConfig().getNodeConfig().getPort();
			for(int j = 0; j < httpStorageNodeCount; j ++) {
				actualConcurrency += PortTools.getConnectionCount("127.0.0.1:" + (startPort + j));
			}
			assertEquals(
				"Expected concurrency: " + actualConcurrency + ", actual: " + actualConcurrency,
				expectedConcurrency, actualConcurrency, expectedConcurrency / 100
			);
		}
	}
}
