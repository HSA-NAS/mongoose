package com.emc.mongoose.run.cli;
// mongoose-common.jar
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.logging.LogUtil;
// mongoose-core-api.jar
import com.emc.mongoose.core.api.data.WSObject;
import com.emc.mongoose.core.api.load.executor.WSLoadExecutor;
// mongoose-scenario.jar
import com.emc.mongoose.run.scenario.Scenario;
import com.emc.mongoose.run.webserver.RunJettyTask;
// mongoose-server-api.jar
import com.emc.mongoose.server.api.load.builder.WSLoadBuilderSvc;
// mongoose-server-impl.jar
import com.emc.mongoose.server.impl.load.builder.BasicWSLoadBuilderSvc;
// mongoose-storage-mock.jar
import com.emc.mongoose.storage.mock.impl.cinderella.Main;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
/**
 Created by kurila on 04.07.14.
 Mongoose entry point.
 */
public final class ModeDispatcher {
	//
	public static void main(final String args[]) {
		//
		final String runMode;
		if(args == null || args.length == 0 || args[0].startsWith("-")) {
			runMode = Constants.RUN_MODE_STANDALONE;
		} else {
			runMode = args[0];
		}
		System.setProperty(RunTimeConfig.KEY_RUN_MODE, runMode);
		//
		final Map<String, String> properties = HumanFriendly.parseCli(args);
		//
		final Logger rootLogger = LogManager.getRootLogger();
		//
		RunTimeConfig.initContext();
		// load the properties
		RunTimeConfig.getContext().loadPropsFromJsonCfgFile(
			Paths.get(RunTimeConfig.DIR_ROOT, Constants.DIR_CONF).resolve(RunTimeConfig.FNAME_CONF)
		);
		rootLogger.debug(LogUtil.MSG, "Loaded the properties from the files");
		RunTimeConfig.getContext().loadSysProps();
		rootLogger.info(LogUtil.MSG, RunTimeConfig.getContext().toString());
		//
		if(!properties.isEmpty()) {
			rootLogger.info(LogUtil.MSG, "Overriding properties {}", properties);
			RunTimeConfig.getContext().overrideSystemProperties(properties);
		}
		//
		switch(runMode) {
			case Constants.RUN_MODE_SERVER:
			case Constants.RUN_MODE_COMPAT_SERVER:
				rootLogger.debug(LogUtil.MSG, "Starting the server");
				try(
					final WSLoadBuilderSvc<WSObject, WSLoadExecutor<WSObject>>
						loadBuilderSvc = new BasicWSLoadBuilderSvc<>(RunTimeConfig.getContext())
				) {
					loadBuilderSvc.start();
					loadBuilderSvc.join();
				} catch(final IOException e) {
					LogUtil.failure(rootLogger, Level.ERROR, e, "Load builder service failure");
				} catch(InterruptedException e) {
					rootLogger.debug(LogUtil.MSG, "Interrupted load builder service");
				}
				break;
			case Constants.RUN_MODE_WEBUI:
				rootLogger.debug(LogUtil.MSG, "Starting the web UI");
				new RunJettyTask(RunTimeConfig.getContext()).run();
				break;
			case Constants.RUN_MODE_CINDERELLA:
			case Constants.RUN_MODE_WSMOCK:
				rootLogger.debug(LogUtil.MSG, "Starting the cinderella");
				try {
					new Main().run();
				} catch (final Exception e) {
					LogUtil.failure(rootLogger, Level.FATAL, e, "Failed");
				}
				break;
			case Constants.RUN_MODE_CLIENT:
			case Constants.RUN_MODE_STANDALONE:
			case Constants.RUN_MODE_COMPAT_CLIENT:
				new Scenario().run();
				break;
			default:
				throw new IllegalArgumentException(
					String.format("Incorrect run mode: \"%s\"", runMode)
				);
		}
	}
	/*
	private static volatile LoggerContext LOG_CONTEXT = null;
	//
	public static void initLogging(final String runMode) {
		//
		System.setProperty("isThreadContextMapInheritable", "true");
		// set "run.id" property with timestamp value if not set before
		String runId = System.getProperty(RunTimeConfig.KEY_RUN_ID);
		if(runId == null || runId.length() == 0) {
			System.setProperty(
				RunTimeConfig.KEY_RUN_ID,
				Settings.FMT_DT.format(
					Calendar.getInstance(Settings.TZ_UTC, Settings.LOCALE_DEFAULT).getTime()
				)
			);
		}
		// make all used loggers asynchronous
		System.setProperty(
			"Log4jContextSelector", AsyncLoggerContextSelector.class.getCanonicalName()
		);
		// connect JUL to Log4J2
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
		// determine the logger configuration file path
		final Path logConfPath = Paths.get(
			RunTimeConfig.DIR_ROOT,
			Constants.DIR_CONF,
			"logging.yaml"
		);
		//
		LOG_CONTEXT = Configurator.initialize("mongoose", logConfPath.toUri().toString());
	}
	//
	public static void initSecurity() {
		// load the security policy
		final String secPolicyURL = "file:" +
			RunTimeConfig.DIR_ROOT + File.separatorChar +
			Constants.DIR_CONF + File.separatorChar +
			Constants.FNAME_POLICY;
		System.setProperty(Constants.KEY_POLICY, secPolicyURL);
		Policy.getPolicy().refresh();
		System.setSecurityManager(new SecurityManager());
	}
	//
	public static void shutdown() {
		if(!LOG_CONTEXT.isStopped()) {
			LOG_CONTEXT.stop();
		}
		ServiceUtils.shutdown();
	}*/
}
//
