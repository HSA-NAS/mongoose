package com.emc.mongoose.run;

import com.emc.mongoose.run.scenario.JsonScenario;
import com.emc.mongoose.run.scenario.Scenario;
import com.emc.mongoose.ui.cli.CliArgParser;
import com.emc.mongoose.ui.config.Config;
import com.emc.mongoose.ui.config.reader.jackson.ConfigParser;
import com.emc.mongoose.common.exception.UserShootHisFootException;
import com.emc.mongoose.ui.log.LogUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 Created by kurila on 11.07.16.
 */
public class Main {

	@SuppressWarnings("unchecked")
	public static void main(final String... args)
	throws Exception {
		
		LogUtil.init();

		final Map<String, Object> cliArgs = CliArgParser.parseArgs(args);
		final Config config = ConfigParser.loadDefaultConfig();
		if(config == null) {
			throw new UserShootHisFootException("Config is null");
		}
		config.apply(cliArgs);

		final String scenarioValue = config.getScenarioConfig().getFile();
		final Path scenarioPath;
		if(scenarioValue != null && !scenarioValue.isEmpty()) {
			scenarioPath = Paths.get(scenarioValue);
		} else {
			scenarioPath = Paths.get(Scenario.DIR_SCENARIO, Scenario.FNAME_DEFAULT_SCENARIO);
		}
		
		try(final Scenario scenario = new JsonScenario(config, scenarioPath.toFile())) {
			scenario.run();
		} catch(final Throwable t) {
			t.printStackTrace(System.err);
		}
	}
}
