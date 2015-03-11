package com.emc.mongoose.run;
//
import com.emc.mongoose.core.impl.persist.TraceLogger;
import com.emc.mongoose.core.impl.util.ThreadContextMap;
import com.emc.mongoose.server.impl.ServiceUtils;
import com.emc.mongoose.server.impl.load.builder.BasicWSLoadBuilderSvc;
import com.emc.mongoose.core.api.data.WSObject;
import com.emc.mongoose.core.api.load.executor.WSLoadExecutor;
import com.emc.mongoose.server.api.load.builder.WSLoadBuilderSvc;
import com.emc.mongoose.core.impl.util.RunTimeConfig;
import com.emc.mongoose.core.api.persist.Markers;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LifeCycle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
//
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Policy;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
/**
 Created by kurila on 04.07.14.
 Mongoose entry point.
 */
public final class Main {
	//
	public final static TimeZone TZ_UTC = TimeZone.getTimeZone("UTC");
	public final static Locale LOCALE_DEFAULT = Locale.ROOT;
	public final static DateFormat FMT_DT = new SimpleDateFormat(
		"yyyy.MM.dd.HH.mm.ss.SSS", LOCALE_DEFAULT
	) {
		{ setTimeZone(TZ_UTC); }
	};
	//
	public final static String
		DOT = ".",
		SEP = System.getProperty("file.separator"),
		DIR_ROOT,
		DIR_CONF = "conf",
		DIR_LOGGING = "logging",
		DIR_PROPERTIES = "properties",
		FNAME_LOGGING_LOCAL = "local.json",
		FNAME_LOGGING_REMOTE = "remote.json",
		FNAME_POLICY = "security.policy",
		//
		KEY_DIR_ROOT = "dir.root",
		KEY_POLICY = "java.security.policy",
		//
		RUN_MODE_STANDALONE = "standalone",
		RUN_MODE_CLIENT = "client",
		RUN_MODE_COMPAT_CLIENT = "controller",
		RUN_MODE_SERVER = "server",
		RUN_MODE_COMPAT_SERVER = "driver",
		RUN_MODE_WEBUI = "webui",
		RUN_MODE_CINDERELLA = "cinderella",
		//
		DEFAULT_ENC = StandardCharsets.UTF_8.name(),
		EMPTY = "";

	//
	public final static File JAR_SELF;
	static {
		String dirRoot = System.getProperty("user.dir");
		File jarSelf = null;
		try {
			jarSelf = new File(
				Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()
			);
			dirRoot = URLDecoder.decode(
				jarSelf.getParent(),
				StandardCharsets.UTF_8.displayName()
			);
		} catch(final UnsupportedEncodingException|URISyntaxException e) {
			e.printStackTrace(System.err);
		} finally {
			DIR_ROOT = dirRoot;
			JAR_SELF = jarSelf;
		}
	}
	//
	public static void main(final String args[]) {
		//
		initSecurity();
		//
		final String runMode;
		if(args == null || args.length == 0 || args[0].startsWith("-")) {
			runMode = RUN_MODE_STANDALONE;
		} else {
			runMode = args[0];
		}
		System.setProperty(RunTimeConfig.KEY_RUN_MODE, runMode);
		//
		final Map<String, String> properties = HumanFriendlyCli.parseCli(args);
		//
		final Logger rootLogger = initLogging(runMode);
		if(rootLogger==null) {
			System.err.println("Logging initialization failure");
			System.exit(1);
		}
		//
		ThreadContextMap.initThreadContextMap();
		/*
		rootLogger.info(
			Markers.MSG, "Run in mode \"{}\", id: \"{}\"",
			System.getProperty(RunTimeConfig.KEY_RUN_MODE),
			System.getProperty(RunTimeConfig.KEY_RUN_ID)
		);*/
		// load the properties
		RunTimeConfig.setContext(new RunTimeConfig());
		//
		RunTimeConfig.getContext().loadPropsFromDir(Paths.get(DIR_ROOT, DIR_CONF, DIR_PROPERTIES));
		rootLogger.debug(Markers.MSG, "Loaded the properties from the files");
		RunTimeConfig.getContext().loadSysProps();
		rootLogger.info(Markers.MSG, RunTimeConfig.getContext().toString());
		//
		if(!properties.isEmpty()) {
			rootLogger.info(Markers.MSG, "Overriding properties {}", properties);
			RunTimeConfig.getContext().overrideSystemProperties(properties);
		}
		//
		switch (runMode) {
			case RUN_MODE_SERVER:
			case RUN_MODE_COMPAT_SERVER:
				rootLogger.debug(Markers.MSG, "Starting the server");
				try(
					final WSLoadBuilderSvc<WSObject, WSLoadExecutor<WSObject>>
						loadBuilderSvc = new BasicWSLoadBuilderSvc<>(RunTimeConfig.getContext())
				) {
					loadBuilderSvc.start();
					loadBuilderSvc.join();
				} catch(final IOException e) {
					TraceLogger.failure(rootLogger, Level.ERROR, e, "Load builder service failure");
				} catch(InterruptedException e) {
					rootLogger.debug(Markers.MSG, "Interrupted load builder service");
				}
				break;
			case RUN_MODE_WEBUI:
				rootLogger.debug(Markers.MSG, "Starting the web UI");
				new JettyRunner(RunTimeConfig.getContext()).run();
				break;
			case RUN_MODE_CINDERELLA:
				rootLogger.debug(Markers.MSG, "Starting the cinderella");
				try {
					new com.emc.mongoose.storage.mock.impl.cinderella.Main(RunTimeConfig.getContext()).run();
				} catch (final Exception e) {
					TraceLogger.failure(rootLogger, Level.FATAL, e, "Failed");
				}
				break;
			case RUN_MODE_CLIENT:
			case RUN_MODE_STANDALONE:
			case RUN_MODE_COMPAT_CLIENT:
				new Scenario().run();
				break;
			default:
				throw new IllegalArgumentException(
					String.format("Incorrect run mode: \"%s\"", runMode)
				);
		}
		//
		shutdown();
	}
	//
	public static Logger initLogging(final String runMode) {
		//
		System.setProperty("isThreadContextMapInheritable", "true");
		// set "dir.root" property
		System.setProperty(KEY_DIR_ROOT, DIR_ROOT);
		// set "run.id" property with timestamp value if not set before
		String runId = System.getProperty(RunTimeConfig.KEY_RUN_ID);
		if(runId==null || runId.length()==0) {
			System.setProperty(
				RunTimeConfig.KEY_RUN_ID,
				FMT_DT.format(Calendar.getInstance(com.emc.mongoose.run.Main.TZ_UTC, com.emc.mongoose.run.Main.LOCALE_DEFAULT).getTime())
			);
		}
		// make all used loggers asynchronous
		System.setProperty(
			"Log4jContextSelector", "org.apache.logging.log4j.core.async.AsyncLoggerContextSelector"
		);
		// StatusConsoleListener statusListener = new StatusConsoleListener(Level.OFF);
		// determine the logger configuration file path
		final Path logConfPath = Paths.get(
			DIR_ROOT, DIR_CONF, DIR_LOGGING,
			(
				runMode.equals(RUN_MODE_STANDALONE) ||
				runMode.equals(RUN_MODE_CLIENT) ||
				runMode.equals(RUN_MODE_COMPAT_CLIENT)
			) ?
				FNAME_LOGGING_LOCAL : FNAME_LOGGING_REMOTE
		);
		// connect JUL to Log4J2
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
		// go
		Configurator.initialize(null, logConfPath.toUri().toString());
		final Logger rootLogger = LogManager.getRootLogger();
		if(rootLogger == null) {
			System.err.println("FATAL: failed to configure the logging");
		} else {
			rootLogger.debug(Markers.MSG, "Used configuration from {}", logConfPath);
		}
		return LogManager.getRootLogger();
	}
	//
	public static void initSecurity() {
		// load the security policy
		final String secPolicyURL = "file:" + DIR_ROOT + SEP + DIR_CONF + SEP + FNAME_POLICY;
		System.setProperty(KEY_POLICY, secPolicyURL);
		Policy.getPolicy().refresh();
		System.setSecurityManager(new SecurityManager());
	}
	//
	public static void shutdown() {
		((LifeCycle) LogManager.getContext()).stop();
		ServiceUtils.shutdown();
	}
}
//
