package com.tokbox.tumor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class ClientPool {
	private static ConcurrentHashMap<Long, NodeInfo> clients = new ConcurrentHashMap<Long, NodeInfo>();
	private static ConcurrentHashMap<Long, Long> timeoutEvictions = new ConcurrentHashMap<Long, Long>();
	private static ScheduledExecutorService scheduledExecutor;
	static {
		scheduledExecutor = Executors.newScheduledThreadPool(1);
		scheduledExecutor.scheduleWithFixedDelay(new TimeoutEvictionRunner(), 1, 300, TimeUnit.SECONDS);
	}

	public static NodeInfo allocateNewClient(Long routerAddressLong) {
		Long clientNetworkAddress = generateRandomNetworkAddress(routerAddressLong);
		while (clients.containsKey(clientNetworkAddress)) {
			clientNetworkAddress = generateRandomNetworkAddress(routerAddressLong);
		}
		NodeInfo newClient = new NodeInfo(clientNetworkAddress);
		clients.put(newClient.getNetworkIdLong(), newClient);
		scheduleTimeoutEviction(newClient);
		System.out.println("created new client: "+newClient.toString());
		return newClient;
	}

	private static void scheduleTimeoutEviction(final NodeInfo client) {
		timeoutEvictions.put(client.getNetworkIdLong(), System.currentTimeMillis() + (300 * 1000));
	}

	public static NodeInfo evictNode(NodeInfo node) {
		return evictNode(node.getNetworkIdLong());
	}

	public static NodeInfo evictNode(long nodeIdLong) {
		timeoutEvictions.remove(nodeIdLong);
		NodeInfo info = clients.remove(nodeIdLong);
		if (null != info) {
			System.out.printf("evicting client %s poolsize=%d\n", info.getNetworkIdOctets(), ClientPool.getOccupiedCount());
		}
		return info;
	}

	private static Long generateRandomNetworkAddress(Long routerAddressLong) {
		Integer clientSerial = (int) (Math.random() * (double)Integer.MAX_VALUE);
		Long clientNetworkAddress = 0L;
		clientNetworkAddress |= routerAddressLong;
		clientNetworkAddress |= clientSerial;
		return clientNetworkAddress;
	}

	public static NodeInfo getClient(long clientNetworkId) {
		NodeInfo info = clients.get(clientNetworkId);
		if (null != info) {
			scheduleTimeoutEviction(info);
		}
		return info;
	}

	private static class TimeoutEvictionRunner implements Runnable {
		public void run() {
			try {
				Set<Long> networkIds = timeoutEvictions.keySet();
				Long evictionStart = System.currentTimeMillis();
				for (Long networkId : networkIds) {
					Long scheduledEviction = timeoutEvictions.get(networkId);
					if (evictionStart > scheduledEviction) {
						System.out.println("timeout "+NodeInfo.longToOctets(networkId));
						evictNode(networkId);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static int getOccupiedCount() {
		return clients.size();
	}
}
