package com.tokbox.tumor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.protobuf.InvalidProtocolBufferException;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
import com.tokbox.tumor.proto.OtspCore.OtspNodeAddress;
import com.tokbox.tumor.security.PrimeCache;

public class UnicastBenchmark {
	private int numClients;
	private int numMessages;

	public UnicastBenchmark(int numClients, int numMessages) {
		this.numClients = numClients;
		this.numMessages = numMessages;
	}
	
	private AtomicInteger routableMessagesReceived = new AtomicInteger(0);
	private Set<Integer> messageIdsIn = new HashSet<Integer>();
	private Set<Integer> messageIdsOut = new HashSet<Integer>();
	private TumorNode.Listener myNodeListener = new MyNodeListener();
	
	private class MyNodeListener implements TumorNode.Listener {

		public void onMessageReceived(OtspMessage message) {
			//System.out.println("received subscribe message: "+routeTestResponseMessage.toString());
			routableMessagesReceived.incrementAndGet();
			synchronized(messageIdsIn) {
				messageIdsIn.add(message.getId());
			}
		}
		
	}
	
	public void runUnicastBenchmark() throws InvalidProtocolBufferException, InterruptedException {
		PrimeCache.getPrime();
		messageIdsIn = (Set<Integer>) Collections.synchronizedSet(messageIdsIn);
		messageIdsOut = (Set<Integer>) Collections.synchronizedSet(messageIdsOut);

		System.out.printf("using %d clients to send %d messages\n", numClients, numMessages);
		final TumorNode[] clients = new TumorNode[numClients];
		long startClientInit = System.currentTimeMillis();
		for (int i = 0; i < clients.length; i++) {
			clients[i] = new TumorNode();
			clients[i].setListener(myNodeListener);
			clients[i].start();
		}
		long clientInitTime = System.currentTimeMillis() - startClientInit;
		System.out.printf("initialized %d clients in %d ms (avg=%.2f ms/c)\n", numClients, clientInitTime, (double)((double)clientInitTime/(double)numClients));

		long messageSendStart = System.currentTimeMillis();
		for (int i = 0; i < numMessages; i++) {
			final int messageId = i;
			messageIdsOut.add(messageId);

			TumorNode firstClient = clients[(int)(Math.random() * (double)clients.length)];
			TumorNode secondClient = clients[(int)(Math.random() * (double)clients.length)];
			//System.out.printf("messageId=%d from=%s to=%s \n",messageId, firstClient.myNode.getNetworkIdOctets(), secondClient.myNode.getNetworkIdOctets());
			OtspNodeAddress firstNodeAddress = firstClient.getNodeInfo().getOtspNodeAddress();
			OtspNodeAddress secondNodeAddress = secondClient.getNodeInfo().getOtspNodeAddress();

			OtspMessage.Builder testMessage = OtspMessage.newBuilder()
					.setTo(secondNodeAddress)
					.setFrom(firstNodeAddress)
					.setId(messageId);

			firstClient.sendMessage(testMessage);

		}
		long messageSendTime = System.currentTimeMillis() - messageSendStart;
		System.out.printf("queued %d message sends in %d ms (avg=%.2f m/ms)\n", numMessages, messageSendTime, (double)((double)numMessages / (double)messageSendTime));

		messageSendTime = System.currentTimeMillis() - messageSendStart;
		System.out.printf("sent %d messages in %d ms (avg=%.2f m/ms)\n", messageIdsOut.size(), messageSendTime, (double)((double)messageIdsOut.size() / (double)messageSendTime));

		while (messageIdsIn.size() < messageIdsOut.size()) {
			Thread.sleep(10);
//			System.out.println("messagesIn.size="+messageIdsIn.size());
//			System.out.println("messagesOut.size="+messageIdsOut.size());
//			System.out.println("routableMessagesReceived="+routableMessagesReceived.intValue());
		}
		
		messageSendTime = System.currentTimeMillis() - messageSendStart;
		System.out.printf("message distribution completed in %d ms (avg=%.2f m/ms)\n", messageSendTime, (double)((double)messageIdsOut.size() / (double)messageSendTime));

		
		System.out.println("messagesIn.size="+messageIdsIn.size());
		System.out.println("messagesOut.size="+messageIdsOut.size());
		System.out.println("routableMessagesReceived="+routableMessagesReceived.intValue());
		synchronized(messageIdsIn) {
			synchronized(messageIdsOut) {
				for (Integer id : messageIdsOut) {
					if (!messageIdsIn.contains(id)) {
						System.out.println("message integrity is questionable lookup "+id);
					} else {
						messageIdsIn.remove(id);
					}
				}
			}
		}
		if (messageIdsIn.isEmpty()) {
			System.out.println("all messages accounted for");
		} else {
			System.out.println("orphaned messages detected");
		}

		for (TumorNode client : clients) {
			client.stop();
		}

		System.out.println("exiting");
		
	}

}
