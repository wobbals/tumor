package com.tokbox.tumor;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.zeromq.ZMQ;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
import com.tokbox.tumor.proto.OtspCore.OtspNodeAddress;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.proto.OtspRouting.ConnectionManagement;
import com.tokbox.tumor.security.DHExchangeGroup;
import com.tokbox.tumor.security.EncryptionService;

/**
 * Although this client may be used in strictly endpoint settings,
 * it is designed to operate as a proxy for other services. Specifically,
 * multiple instances may be run in the same process with reasonable 
 * efficiency. We will make sure resource consumption is bounded and somewhat reasonable.
 * This means there are a number of todos:
 * - polling subscribers and/or subscriber aggregation
 * - async API for sendMessage (and ack/error handling)
 *
 */
public class TumorClient {
	private static ZMQ.Context context = ZMQ.context(1);

	private ZMQ.Socket sender;
	private ZMQ.Socket receiver;
	private RoutableNode myNode;
	private RoutableNode myRouter;
	private ExecutorService executor;
	private AtomicInteger routableMessageSeqno;

	public TumorClient() throws InvalidProtocolBufferException {
		routableMessageSeqno = new AtomicInteger(1);

		//  Prepare our context and socket
		sender = context.socket(ZMQ.REQ);

		//System.out.println("Connecting to tumor router...");
		sender.connect("tcp://localhost:5555");

		DHExchangeGroup handshake = DHExchangeGroup.generateHandshake();
		ConnectionManagement connectionRequest = ConnectionManagement.newBuilder(handshake.toConnectionManagementMessage())
				.setOpcode(ConnectionManagement.OpCode.CONNECT)
				.build();
		OtspMessage message = 
				OtspMessage.newBuilder()
				.setExtension(OtspRouting.connectionManagement, connectionRequest)
				.build();
		byte[] messageBytes = message.toByteArray();
		byte[] request = new byte[messageBytes.length+1];
		System.arraycopy(messageBytes, 0, request, 0, messageBytes.length);
		request[request.length - 1] = 0;

		//System.out.println("sending D-H handshake");
		sender.send(request, 0);

		//  Get the reply.
		byte[] reply = sender.recv(0);
		ExtensionRegistry registry = ExtensionRegistry.newInstance();
		registry.add(OtspRouting.connectionManagement);
		OtspMessage response = OtspMessage.parseFrom(ByteString.copyFrom(reply, 0, reply.length - 1), registry);
		//System.out.println("Received D-H response");

		if (response.hasExtension(OtspRouting.connectionManagement)) {
			OtspRouting.ConnectionManagement connectionManagementMessage = response.getExtension(OtspRouting.connectionManagement);
			//System.out.println("ConnectionManagment: "+connectionManagementMessage.toString());
			handshake.consumeResponse(connectionManagementMessage);
		}
		// declare initial routing state 
		myNode = new RoutableNode(response.getTo().getAddress().asReadOnlyByteBuffer().getLong());
		myNode.setSharedSecret(handshake.getSharedSecret());
		//System.out.println("my address: "+myNode);
		myRouter = new RoutableNode(response.getFrom().getAddress().asReadOnlyByteBuffer().getLong());
		//System.out.println("my router: "+myRouter);

		receiver = context.socket(ZMQ.SUB);
		receiver.connect(String.format("tcp://%s:5556", myNode.getRouterAddress().getHostAddress()));
		//System.out.println("subscribing to "+myNode.getNetworkIdOctets());
		receiver.subscribe(myNode.getNetworkId());
		
		//TODO route check to self, coordinate with subscriber loop
	}

	public void start() {
		if (null != executor && !executor.isShutdown()) {
			executor.shutdown();
		}
		executor = Executors.newFixedThreadPool(1);
		executor.submit(new SubscribeLoop());
	}

	public void stop() {
		executor.shutdownNow();
	}

	public void sendRoutableMessage(OtspMessage.Builder messageBuilder) {
		if (!messageBuilder.hasId()) {
			messageBuilder.setId(routableMessageSeqno.incrementAndGet());
		}
		EncryptionService.signMessage(messageBuilder, myNode);
		byte[] messageData = messageBuilder.build().toByteArray();
		ByteBuffer sendBuffer = ByteBuffer.allocate(messageData.length+1);
		sendBuffer.put(messageData);
		sendBuffer.put((byte) 0);
		sender.send(sendBuffer.array(), 0);

		//TODO read response for errors
		sender.recv(0);
		while (sender.hasReceiveMore()) {
			sender.recv(0);
		}
	}

	private class SubscribeLoop implements Runnable {

		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					byte[] incomingMessage = receiver.recv(0);

					if (receiver.hasReceiveMore()) {
						//System.out.println("received subscribe envelope: "+RoutableNode.bytesToOctets(incomingMessage));
						incomingMessage = receiver.recv(0);
					}
					incomingMessage = EncryptionService.decrypt(incomingMessage, myNode);

					OtspMessage routeTestResponseMessage;
					routeTestResponseMessage = OtspMessage.parseFrom(ByteString.copyFrom(incomingMessage, 0, incomingMessage.length-1));

					//System.out.println("received subscribe message: "+routeTestResponseMessage.toString());
					routableMessagesReceived.incrementAndGet();
					synchronized(messageIdsIn) {
						messageIdsIn.add(routeTestResponseMessage.getId());
					}
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	private static AtomicInteger routableMessagesReceived = new AtomicInteger(0);
	private static HashSet<Integer> messageIdsIn = new HashSet<Integer>();
	private static HashSet<Integer> messageIdsOut = new HashSet<Integer>();

	public static void main(String[] args) throws InvalidProtocolBufferException {

		int numClients = 10;
		if (args.length > 1) {
			numClients = Integer.parseInt(args[0]);
		}
		int numMessages = 100000;
		if (args.length > 2) {
			numMessages = Integer.parseInt(args[1]);
		}
		System.out.printf("using %d clients to send %d messages\n", numClients, numMessages);
		TumorClient[] clients = new TumorClient[numClients];
		long startClientInit = System.currentTimeMillis();
		for (int i = 0; i < clients.length; i++) {
			clients[i] = new TumorClient();
			clients[i].start();
		}
		long clientInitTime = System.currentTimeMillis() - startClientInit;
		System.out.printf("initialized %d clients in %d ms (avg=%.2f ms/c)\n", numClients, clientInitTime, (double)((double)clientInitTime/(double)numClients));

		long messageSendStart = System.currentTimeMillis();
		for (int i = 1; i <= numMessages; i++) {
			TumorClient firstClient = clients[(int)(Math.random() * (double)clients.length)];
			TumorClient secondClient = clients[(int)(Math.random() * (double)clients.length)];
			OtspNodeAddress firstNodeAddress = firstClient.myNode.getOtspNodeAddress();
			OtspNodeAddress secondNodeAddress = secondClient.myNode.getOtspNodeAddress();

			OtspMessage.Builder testMessage = OtspMessage.newBuilder()
					.setTo(secondNodeAddress)
					.setFrom(firstNodeAddress)
					.setId(i);
			firstClient.sendRoutableMessage(testMessage);
			messageIdsOut.add(i);
		}

		long messageSendTime = System.currentTimeMillis() - messageSendStart;
		System.out.printf("sent %d messages in %d ms (avg=%.2f m/ms)\n", numMessages, messageSendTime, (double)((double)numMessages / (double)messageSendTime));



		while(routableMessagesReceived.intValue() != numMessages) {
			System.out.printf("%d missing messages detected \n", numMessages - routableMessagesReceived.intValue());

			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} 
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		System.out.println("messagesIn.size="+messageIdsIn.size());
		System.out.println("messagesOut.size="+messageIdsOut.size());
		synchronized(messageIdsIn) {
			for (Integer id : messageIdsOut) {
				if (!messageIdsIn.contains(id)) {
					System.out.println("message integrity is questionable lookup "+id);
				} else {
					messageIdsIn.remove(id);
				}
			}
		}
		if (messageIdsIn.isEmpty()) {
			System.out.println("all messages accounted for");
		} else {
			System.out.println("orphaned messages detected");
		}

		for (TumorClient client : clients) {
			client.stop();
		}

		System.out.println("exiting");
		System.exit(0);
	}
}
