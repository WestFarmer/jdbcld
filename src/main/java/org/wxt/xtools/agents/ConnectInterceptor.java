package org.wxt.xtools.agents;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.wxt.xtools.agents.utils.StringUtils;

import ch.qos.logback.classic.Logger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.Advice.This;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;

/**
 * 
 * @author ggfan
 *
 */
public class ConnectInterceptor {
	
	// fields below must be public, as we will access them from the intercepting methods 
	public static Map<Integer, ConnectionCreationInfo> conns = new HashMap<Integer, ConnectionCreationInfo>();

	public static Map<String, Integer> counstsByStack = new HashMap<String, Integer>();
	
	public static Logger log = JDBCLeakDetector.log;
	
	public static List<ConnectionCreationInfo> getInfo(){
		List<ConnectionCreationInfo> data = new ArrayList<ConnectionCreationInfo>();
		for(ConnectionCreationInfo ci : conns.values()) {
			ConnectionCreationInfo copy = new ConnectionCreationInfo();
			copy.setHash(ci.getHash());
			copy.setStackHash(ci.getStackHash() + "(" + counstsByStack.get(ci.getStackHash()) + ")");
			copy.setCreationTime(ci.getCreationTime());
			copy.setLastActiveTime(ci.getLastActiveTime());
			data.add(copy);
		}
		return data;
	}
	
	public static String getStack(String shash) {
		for(ConnectionCreationInfo ci : conns.values()) {
			if(ci.getStackHash().equals(shash)) {
				return ci.getStack();
			}
		}
		return null;
	}

	@RuntimeType
	public static Object interceptor(@Origin Class<?> clazz, @Origin Method method, @SuperCall Callable<?> callable,
			@net.bytebuddy.implementation.bind.annotation.This Object inst) throws Exception {
		Object o = callable.call();
		int hashCode = System.identityHashCode(inst);
		ConnectionCreationInfo ci = conns.get(hashCode);
		if(ci == null) {
			log.error("Connection@{} is not recorded", hashCode);
		}
		if (method.getName().equals("close")) {
			log.info("Connnection@{} released", hashCode);
			
			String stackHash = ci.getStackHash();
			Integer scount = counstsByStack.get(stackHash);
			if(scount != null && scount > 0) {
				int newscount = scount - 1;
				log.debug("set connection count to {} by stack hash {}", newscount, stackHash);
				if(newscount == 0) {
					counstsByStack.remove(stackHash);
				}else {
					counstsByStack.put(stackHash, newscount);
				}
			}else {
				log.error("Connection count by stack hash {} is not supposed to be null or less than zero", stackHash);
			}
			
			conns.remove(hashCode);
		} else {
			log.debug("Connnection@{} used by {}", hashCode, method.getName());
			ci.setLastActiveTime(System.currentTimeMillis());
		}
		return o;
	}

	@Advice.OnMethodExit
	public static void intercept(@net.bytebuddy.asm.Advice.Origin Constructor<?> m, @This Object inst)
			throws Exception {
		// some CP library override hashCode method
		int hashCode = System.identityHashCode(inst);
		ConnectionCreationInfo ci = new ConnectionCreationInfo();
		ci.setHash(hashCode);
		ci.setCreationTime(System.currentTimeMillis());
		StringWriter sw = new StringWriter();
		Throwable t = new Throwable("");
		t.printStackTrace(new PrintWriter(sw));
		String stackTrace = sw.toString();
		sw.close();
		ci.setStack(stackTrace);
		String shash = StringUtils.md5(stackTrace);
		ci.setStackHash(shash);
		log.info("Connnection@{} acquired by {}", hashCode, shash);
		log.debug("Connection creation call stack: ", t);
		conns.put(hashCode, ci);
		Integer scount = counstsByStack.get(ci.getStackHash());
		if(scount == null) {
			counstsByStack.put(ci.getStackHash(), 1);
		}else {
			counstsByStack.put(ci.getStackHash(), scount + 1);
		}
	}

}
