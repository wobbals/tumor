package com.tokbox.tumor;

import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.zeromq.ZMQ;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
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
public class TumorNode {
	private static ZMQ.Context context = ZMQ.context(1);

	private static ExtensionRegistry registry;
	static {
		registry = ExtensionRegistry.newInstance();
		registry.add(OtspRouting.connectionManagement);
	}
	
	private ZMQ.Socket sender;
	private ZMQ.Socket receiver;
	private NodeInfo nodeInfo;
	private NodeInfo routerInfo;
	private ExecutorService executor;
	private AtomicInteger routableMessageSeqno;
	private Listener listener;
	private ListenerDelegate listenerDelegate = new ListenerDelegate();
	
	public static interface Listener {
		public void onMessageReceived(OtspMessage message);
	}
	
	private class ListenerDelegate {
		public void signalMessageReceived(final OtspMessage message) {
			final Listener myListener = getListener();
			if (null == myListener) return;
			executor.submit(new Runnable() {
				public void run() {
					myListener.onMessageReceived(message);
				}});
		}
	}
	
	public TumorNode() throws InvalidProtocolBufferException {
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
		OtspMessage response = OtspMessage.parseFrom(ByteString.copyFrom(reply, 0, reply.length - 1), registry);
		//System.out.println("Received D-H response");

		if (response.hasExtension(OtspRouting.connectionManagement)) {
			OtspRouting.ConnectionManagement connectionManagementMessage = response.getExtension(OtspRouting.connectionManagement);
			//System.out.println("ConnectionManagment: "+connectionManagementMessage.toString());
			handshake.consumeResponse(connectionManagementMessage);
		}
		// declare initial routing state 
		nodeInfo = new NodeInfo(response.getTo().getAddress().asReadOnlyByteBuffer().getLong());
		nodeInfo.setSharedSecret(handshake.getSharedSecret());
		//System.out.println("my address: "+myNode);
		routerInfo = new NodeInfo(response.getFrom().getAddress().asReadOnlyByteBuffer().getLong());
		//System.out.println("my router: "+myRouter);

		receiver = context.socket(ZMQ.SUB);
		receiver.connect(String.format("tcp://%s:5556", nodeInfo.getRouterAddress().getHostAddress()));
		//System.out.println("subscribing to "+myNode.getNetworkIdOctets());
		receiver.subscribe(nodeInfo.getNetworkId());

		//TODO route check to self, coordinate with subscriber loop
	}

	public void start() {
		if (null != executor && !executor.isShutdown()) {
			executor.shutdown();
		}
//		executor = Executors.newCachedThreadPool();
		executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		executor.submit(new SubscribeLoop());
		//executor.submit(new SendLoop());
	}

	public void stop() {
		OtspRouting.ConnectionManagement bye = OtspRouting.ConnectionManagement.newBuilder()
				.setOpcode(OtspRouting.ConnectionManagement.OpCode.BYE).build();
		OtspMessage.Builder messageBuilder = OtspMessage.newBuilder()
				.setFrom(getNodeInfo().getOtspNodeAddress())
				.setTo(getRouterInfo().getOtspNodeAddress())
				.setExtension(OtspRouting.connectionManagement, bye);
		try {
			this.sendMessage(messageBuilder).get();
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
		executor.shutdownNow();
		
		//TODO do I need to disconnect too?
		sender.close();
		receiver.close();
	}

	public Future<OtspMessage> sendMessage(OtspMessage.Builder messageBuilder) {
		if (!messageBuilder.hasId()) {
			messageBuilder.setId(routableMessageSeqno.incrementAndGet());
		}
		messageBuilder.setFrom(getNodeInfo().getOtspNodeAddress());
		EncryptionService.signMessage(messageBuilder, nodeInfo);
		byte[] messageData = messageBuilder.build().toByteArray();
		ByteBuffer sendBuffer = ByteBuffer.allocate(messageData.length+1);
		sendBuffer.put(messageData);
		sendBuffer.put((byte) 0);

//		try {
//			sendQueue.put(sendBuffer.array());
//		} catch (InterruptedException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		return null;
		return executor.submit(new SendTask(sendBuffer.array()));
		
	}

	private class SendTask implements Callable<OtspMessage> {
		private byte[] message;
		public SendTask(byte[] message) {
			this.message = message;
		}
		public OtspMessage call() throws Exception {
			synchronized(sendQueue) { //TODO make real lock if we keep this approach
				sender.send(message, 0);
				byte[] responseBytes = sender.recv(0);
				while (sender.hasReceiveMore()) {
					System.out.println("shit is weird");
					sender.recv(0);
				}
				try {
					return OtspMessage.parseFrom(responseBytes, registry);
				} catch (InvalidProtocolBufferException ipbe) {
					return null;
				}
			}
		}
	}
	
	private LinkedBlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<byte[]>();
	private class SendLoop implements Runnable {

		public void run() {
			try {
				while(!Thread.currentThread().isInterrupted()) {
					byte[] message = sendQueue.take();
					sender.send(message, 0);

					//TODO read response for errors, dispatch handler
					sender.recv(0);
					while (sender.hasReceiveMore()) {
						sender.recv(0);
					}
				}
			} catch (InterruptedException e) {
				//System.out.println("shutting down sendloop");
			}
		}

	}

	private class SubscribeLoop implements Runnable {

		public void run() {
			ZMQ.Poller poller = context.poller(1);
			int pollIndex = poller.register(receiver, ZMQ.Poller.POLLIN /* todo: pollerr handler */);

			while (!Thread.currentThread().isInterrupted()) {
				try {
					poller.poll(1000);
					if (poller.pollin(pollIndex)) {
						byte[] incomingMessage = receiver.recv(0);

						//System.out.println(Thread.currentThread().getName() + ": received subscribe envelope: "+RoutableNode.bytesToOctets(incomingMessage, incomingMessage.length - 1));

						incomingMessage = receiver.recv(0);
						//System.out.println(Thread.currentThread().getName() + ": incoming message length: "+incomingMessage.length);
						incomingMessage = EncryptionService.decrypt(nodeInfo, incomingMessage, 0, incomingMessage.length - 1);

						OtspMessage otspResponseMessage;
						otspResponseMessage = OtspMessage.parseFrom(ByteString.copyFrom(incomingMessage, 0, incomingMessage.length-1));
						
						listenerDelegate.signalMessageReceived(otspResponseMessage);
						
					}

				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			//System.out.println("shutting down subscribe loop");
		}
	}

	public NodeInfo getNodeInfo() {
		return this.nodeInfo;
	}

	public Listener getListener() {
		return listener;
	}

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	public NodeInfo getRouterInfo() {
		return routerInfo;
	}
	
}
