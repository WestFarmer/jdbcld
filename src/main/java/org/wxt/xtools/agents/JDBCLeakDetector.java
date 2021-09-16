package org.wxt.xtools.agents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;
import org.wxt.xtools.agents.utils.StringUtils;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import static net.bytebuddy.matcher.ElementMatchers.*;
import net.bytebuddy.utility.JavaModule;

/**
 * A agent for helping detecting JDBC connection leak in java. You can run this
 * with below JVM options: <br />
 * <span style=
 * "padding-left:2em;color:blue">-javaagent:/path/to/agent.jar:agentArgs</span>
 * <br />
 * agentArgs must follow pattern of: <br />
 * <span style=
 * "padding-left:2em;color:red">log_dir,log_level,connection_class</span><br />
 * <ul>
 * <li>log_dir: where to output agent logs, if no lead '/' specified, will be a
 * relative path to targeting JVM's working directory.</li>
 * <li>log_level: logging level for this agent, can be ALL | TRACE | DEBUG |
 * INFO | WARN | ERROR | OFF, default to DEBUG.</li>
 * <li>connection_class: target class to intercept, usually should be a wrapping
 * class provide by connection pool libraries.</li>
 * </ul>
 * 
 * @author ggfan
 *
 */
public class JDBCLeakDetector {

	protected static Logger log;

	public static void premain(String agentArgs, Instrumentation inst) throws InstantiationException, IOException {
		System.out.println("========================== JDBC Leak Detector Agent ==========================");

		String[] args = agentArgs.split(",");

		LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();

		PatternLayoutEncoder logEncoder = new PatternLayoutEncoder();
		logEncoder.setContext(logCtx);
		logEncoder.setPattern("%-12date{YYYY-MM-dd HH:mm:ss.SSS} %-5level - %msg%n");
		logEncoder.start();
		FileAppender<ILoggingEvent> logFileAppender = new FileAppender<ILoggingEvent>();
		logFileAppender.setContext(logCtx);
		logFileAppender.setName("logFile");
		logFileAppender.setEncoder(logEncoder);
		logFileAppender.setAppend(false);
		logFileAppender.setFile(args[0] + "/jdbcld.log");
		logFileAppender.start();
		log = logCtx.getLogger("JDBCLeakDetector");
		log.setAdditive(false);
		log.setLevel(Level.toLevel(args[1]));
		log.addAppender(logFileAppender);

		HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

		server.createContext("/report", new ReportHandler());
		server.createContext("/data", new DataHandler());
		server.createContext("/stack", new StackHandler());
		ExecutorService es = Executors.newCachedThreadPool(new ThreadFactory() {
			int count = 0;

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setDaemon(true);
				t.setName("JDBCLD-HTTP-SERVER" + count++);
				return t;
			}

		});
		server.setExecutor(es);
		server.start();

		// how to properly close ?
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				server.stop(5);
				log.info("internal httpserver has been closed.");
				es.shutdown();
				try {
					if (!es.awaitTermination(60, TimeUnit.SECONDS)) {
						log.warn("executor service of internal httpserver not closing in 60 seconds");
						es.shutdownNow();
						if (!es.awaitTermination(60, TimeUnit.SECONDS))
							log.error("executor service of internal httpserver not closing in 120 seconds, give up");
					} else {
						log.info("executor service of internal httpserver closed.");
					}
				} catch (InterruptedException ie) {
					log.warn("thread interrupted, shutdown executor service of internal httpserver");
					es.shutdownNow();
					Thread.currentThread().interrupt();
				}
			}
		});

		AgentBuilder.Transformer constructorTransformer = new AgentBuilder.Transformer() {
			@Override
			public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
					ClassLoader classLoader, JavaModule javaModule) {
				return builder.visit(Advice.to(ConnectInterceptor.class).on(isConstructor()));
			}
		};

		AgentBuilder.Transformer methodsTransformer = new AgentBuilder.Transformer() {
			@Override
			public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription,
					ClassLoader classLoader, JavaModule javaModule) {
				return builder.method(namedOneOf("close", "unwrap", "isWrapperFor", "createStatement", "prepareStatement", "prepareCall", "nativeSQL",
						"setAutoCommit", "getAutoCommit", "commit", "rollback", "close", "isClosed", "getMetaData",
						"setReadOnly", "isReadOnly", "setCatalog", "getCatalog", "setTransactionIsolation",
						"getTransactionIsolation", "getWarnings", "clearWarnings", "createStatement",
						"prepareStatement", "prepareCall", "getTypeMap", "setTypeMap", "setHoldability",
						"getHoldability", "setSavepoint", "setSavepoint", "rollback", "releaseSavepoint",
						"createStatement", "prepareStatement", "prepareCall", "prepareStatement", "prepareStatement",
						"prepareStatement", "createClob", "createBlob", "createNClob", "createSQLXML", "isValid",
						"setClientInfo", "setClientInfo", "getClientInfo", "getClientInfo", "createArrayOf",
						"createStruct", "setSchema", "getSchema", "abort", "setNetworkTimeout", "getNetworkTimeout").and(not(isAbstract())))
						.intercept(MethodDelegation.to(ConnectInterceptor.class));
			}
		};

		AgentBuilder.Listener listener = new AgentBuilder.Listener.WithErrorsOnly(
				new AgentBuilder.Listener.StreamWriting(System.out));

		new AgentBuilder.Default().type(named(args[2])).transform(constructorTransformer).transform(methodsTransformer)
				.with(listener).installOn(inst);

	}

	static class ReportHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			// RuntimeException will be ignored silently, but we want to know them
			try {
				t.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
				OutputStream os = t.getResponseBody();
				InputStream is = JDBCLeakDetector.class.getResourceAsStream("/report.html");
				ByteArrayOutputStream tmpos = new ByteArrayOutputStream();
				int nRead = 0, total = 0;
				byte[] data = new byte[4096];
				while ((nRead = is.read(data, 0, data.length)) != -1) {
					total += nRead;
					tmpos.write(data, 0, nRead);
				}
				tmpos.flush();
				t.getResponseHeaders().set("Content-Length", "" + total);
				t.sendResponseHeaders(200, total);
				os.write(tmpos.toByteArray());
				os.flush();
				os.close();
				tmpos.close();
			} catch (Exception e) {
				log.error("jdbcld internal http server error", e);
				throw e;
			}
		}
	}

	static class DataHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				OutputStream os = t.getResponseBody();
				t.getResponseHeaders().set("Content-Type", "applcation/json");
				Gson gson = new Gson();
				String s = gson.toJson(ConnectInterceptor.getInfo());
				t.getResponseHeaders().set("Content-Length", "" + s.getBytes().length);
				t.sendResponseHeaders(200, s.getBytes().length);
				os.write(s.getBytes());
				os.close();
			} catch (Exception e) {
				log.error("jdbcld internal http server error", e);
				throw e;
			}
		}
	}

	static class StackHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange t) throws IOException {
			try {
				Map<String, String> paras = StringUtils.queryToMap(t.getRequestURI().getQuery());
				if (paras != null) {
					String s = paras.get("shash");
					if (s != null) {
						log.debug("loading stack trace for {}", s);
						String stack = ConnectInterceptor.getStack(s);
						t.sendResponseHeaders(200, stack.getBytes().length);
						t.getResponseBody().write(stack.getBytes());
						t.getResponseBody().flush();
						t.getRequestBody().close();
					}
				}
			} catch (Exception e) {
				log.error("jdbcld internal http server error", e);
				throw e;
			}
		}
	}

}
