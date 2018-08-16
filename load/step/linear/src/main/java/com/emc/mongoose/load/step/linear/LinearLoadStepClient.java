package com.emc.mongoose.load.step.linear;

import com.emc.mongoose.env.Extension;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.load.step.client.LoadStepClient;
import com.emc.mongoose.load.step.client.LoadStepClientBase;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.metrics.MetricsManager;
import com.github.akurilov.commons.reflection.TypeUtil;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.confuse.Config;

import java.util.List;

public class LinearLoadStepClient
extends LoadStepClientBase {

	public LinearLoadStepClient(
		final Config baseConfig, final List<Extension> extensions, final List<Config> contextConfigs,
		final MetricsManager metricsManager
	) {
		super(baseConfig, extensions, contextConfigs, metricsManager);
	}

	@Override @SuppressWarnings("unchecked")
	protected <T extends LoadStepClient> T copyInstance(final Config config, final List<Config> ctxConfigs) {
		return (T) new LinearLoadStepClient(config, extensions, ctxConfigs, metricsMgr);
	}

	@Override
	protected void init()
	throws IllegalStateException {

		final String autoStepId = "linear_" + LogUtil.getDateTimeStamp();
		if(config.boolVal("load-step-idAutoGenerated")) {
			config.val("load-step-id", autoStepId);
		}

		final OpType opType = OpType.valueOf(config.stringVal("load-op-type").toUpperCase());
		final int concurrencyLimit = config.intVal("storage-driver-limit-concurrency");
		final Config outputConfig = config.configVal("output");
		final Config metricsConfig = outputConfig.configVal("metrics");
		final SizeInBytes itemDataSize;
		final Object itemDataSizeRaw = config.val("item-data-size");
		if(itemDataSizeRaw instanceof String) {
			itemDataSize = new SizeInBytes((String) itemDataSizeRaw);
		} else {
			itemDataSize = new SizeInBytes(TypeUtil.typeConvert(itemDataSizeRaw, long.class));
		}
		final int originIndex = 0;
		final boolean colorFlag = outputConfig.boolVal("color");

		initMetrics(originIndex, opType, concurrencyLimit, metricsConfig, itemDataSize, colorFlag);
	}

	@Override
	public String getTypeName() {
		return LinearLoadStepExtension.TYPE;
	}
}