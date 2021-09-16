package org.wxt.xtools.agents;

/**
 * 
 * @author ggfan
 *
 */
public class ConnectionCreationInfo {
	
	private int hash;
	
	private String stackHash;
	
	private long creationTime;
	
	private long lastActiveTime;
	
	private String stack;

	public int getHash() {
		return hash;
	}

	public void setHash(int hash) {
		this.hash = hash;
	}

	public String getStackHash() {
		return stackHash;
	}

	public void setStackHash(String stackHash) {
		this.stackHash = stackHash;
	}

	public long getCreationTime() {
		return creationTime;
	}

	public void setCreationTime(long creationTime) {
		this.creationTime = creationTime;
	}

	public long getLastActiveTime() {
		return lastActiveTime;
	}

	public void setLastActiveTime(long lastActiveTime) {
		this.lastActiveTime = lastActiveTime;
	}

	public String getStack() {
		return stack;
	}

	public void setStack(String stack) {
		this.stack = stack;
	}

}