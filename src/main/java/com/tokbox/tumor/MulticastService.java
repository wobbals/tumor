package com.tokbox.tumor;

import java.util.HashMap;
import java.util.TreeSet;

/**
 * OTSP routing: group message handling:
 * 
 * from parent:
 * query: reply positive own address else forward query to children from self
 * report: add parent to receipt pool
 * leave: remove parent from receipt pool
 * 		
 * from child (node):
 * query: create group on node's behalf, send query to parent from self
 * report: add node to receipt pool, if first send report to parent from self
 * leave: remove node from receipt pool, if pool empty send leave to parent from self
 * 
 * @author charley
 *
 */
public class MulticastService {
	//map MCAST_ADDR --> set NODE_ADDR
	private HashMap<Long, TreeSet<Long>> multicastDictionary;
	
	public synchronized boolean isActiveMulticastAddress(Long multicastAddress) {
		return false;
	}
	
	public synchronized boolean reportReceived(Long nodeAddress, Long multicastAddress) {
		return false;
	}
	
	//we need a broadcast address to spool inbound query from parent
}
