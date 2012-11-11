package com.tokbox.tumor;

import java.util.HashMap;

public class ClientPool {
	private static HashMap<Long, NodeInfo> clients = new HashMap<Long, NodeInfo>();
		
	public static NodeInfo allocateNewClient() {
		Long clientNetworkAddress = generateRandomNetworkAddress();
		while (clients.containsKey(clientNetworkAddress)) {
			clientNetworkAddress = generateRandomNetworkAddress();
		}
		NodeInfo newClient = new NodeInfo(clientNetworkAddress);
		clients.put(newClient.getNetworkIdLong(), newClient);
		System.out.println("created new client: "+newClient.toString());
		return newClient;
	}
	
	public static void evictClient(NodeInfo node) {
		clients.remove(node.getNetworkIdLong());
	}

	private static Long generateRandomNetworkAddress() {
		Integer clientSerial = (int) (Math.random() * (double)Integer.MAX_VALUE);
		Integer bindAddress = 0x7F000001; 
		Long clientNetworkAddress = (long) bindAddress;
		clientNetworkAddress <<= 32;
		clientNetworkAddress |= clientSerial;
		return clientNetworkAddress;
	}

	public static NodeInfo getClient(long clientNetworkId) {
		return clients.get(clientNetworkId);
	}
}
