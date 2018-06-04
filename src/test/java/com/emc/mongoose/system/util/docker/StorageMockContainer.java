package com.emc.mongoose.system.util.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class StorageMockContainer
extends ContainerBase {

	private static final Logger LOG = Logger.getLogger(StorageMockContainer.class.getSimpleName());
	private static final String IMAGE_NAME = "emcmongoose/nagaina";

	private final String itemInputFile;
	private final String itemNamingPrefix;
	private final int itemNamingRadix;
	private final int capacity;
	private final int containerCapacity;
	private final int containerCountLimit;
	private final int failConnectEvery;
	private final int failResponsesEvery;
	private final int port;
	private final boolean sslFlag;
	private final double rateLimit;

	public StorageMockContainer(
		final int port, final boolean sslFlag, final String itemInputFile,
		final String itemNamingPrefix, final int itemNamingRadix,
		final int capacity, final int containerCountLimit, final int containerCapacity,
		final int failConnectEvery, final int failResponsesEvery, final double rateLimit
	) throws Exception {
		this.itemInputFile = itemInputFile;
		this.itemNamingPrefix = itemNamingPrefix;
		this.itemNamingRadix = itemNamingRadix;
		this.capacity = capacity;
		this.containerCapacity = containerCapacity;
		this.containerCountLimit = containerCountLimit;
		this.failConnectEvery = failConnectEvery;
		this.failResponsesEvery = failResponsesEvery;
		this.port = port;
		this.sslFlag = sslFlag;
		this.rateLimit = rateLimit;
	}

	@Override
	protected final String imageName() {
		return IMAGE_NAME;
	}

	@Override
	protected List<String> containerArgs() {
		final List<String> cmd = new ArrayList<>();
		cmd.add("-Xms1g");
		cmd.add("-Xmx1g");
		cmd.add("-XX:MaxDirectMemorySize=1g");
		cmd.add("-jar");
		cmd.add("/opt/nagaina/nagaina.jar");
		if(itemInputFile != null && !itemInputFile.isEmpty()) {
			cmd.add("--item-input-file=" + itemInputFile);
		}
		if(itemNamingPrefix != null) {
			cmd.add("--item-naming-prefix=" + itemNamingPrefix);
		}
		cmd.add("--item-naming-radix=" + itemNamingRadix);
		cmd.add("--storage-mock-capacity=" + capacity);
		cmd.add("--storage-mock-container-capacity=" + containerCapacity);
		cmd.add("--storage-mock-container-countLimit=" + containerCountLimit);
		cmd.add("--storage-mock-fail-connections=" + failConnectEvery);
		cmd.add("--storage-mock-fail-responses=" + failResponsesEvery);
		cmd.add("--storage-net-node-port=" + port);
		cmd.add("--storage-net-ssl=" + sslFlag);
		cmd.add("--test-step-limit-rate=" + rateLimit);
		return cmd;
	}

	@Override
	protected int[] exposedTcpPorts() {
		return new int[] { port };
	}

	@Override
	protected String entrypoint() {
		return "java";
	}
}
