package com.tokbox.tumor;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.zeromq.ZMQ;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
import com.tokbox.tumor.proto.OtspCore.OtspNodeAddress;
import com.tokbox.tumor.proto.OtspRouting.ConnectionManagement;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.security.DHExchangeGroup;
import com.tokbox.tumor.security.EncryptionService;
import com.tokbox.tumor.security.PrimeCache;

/**
 * Some thoughts.
 * Network Aggregation
 * While we can generally expect comparatively better performance of unicast routing
 * with a distributed routing hierarchy (larger routing table), clusters will need to 
 * elect (or preconfigure) a gateway to forward for the subnet. Specifically, broadcast
 * and multicast implementations require network routers. 
 * Additionally, the population of routing nodes
 * could become large (and heterogeneous?) enough to justify network aggregation even
 * for purely unicast service. Alternately, dynamic peer discovery could enable 
 * a large, p2p-like, routing table for unicast. 
 * Also not considered: a (non gateway hardware) tunnel between clusters could mandate  
 * the use of a network aggregation strategy.
 * 
 * Likely safer to bind a single address per socket for routing, but this needs more consideration.
 * At a minimum, register each address that gets picked up in the bind to the router service.
 *
 */
public class TumorRouter {
	private ExecutorService executor;
	private ZMQ.Socket[] replyServiceWorkers;
	private ZMQ.Socket publishService;
	private ZMQ.Socket requestServiceRouter;
	private ZMQ.Socket requestServiceDealer;
	private ZMQ.Context context;
	private int numWorkers;
	private SendLoop sendLoop;
	
	private static ExtensionRegistry registry;
	static {
		registry = ExtensionRegistry.newInstance();
		registry.add(OtspRouting.connectionManagement);
		registry.add(OtspRouting.groupManagement);
		registry.add(OtspRouting.signature);
	}

	public TumorRouter() {
		this(1);
	}

	public TumorRouter(int numWorkers) {
		this.numWorkers = numWorkers;
		//  Prepare our context and socket
		context = ZMQ.context(1);
		publishService = context.socket(ZMQ.PUB);
		publishService.bind("tcp://*:5556");

		requestServiceRouter = context.socket(ZMQ.ROUTER);
		requestServiceRouter.bind("tcp://*:5555");
		requestServiceDealer = context.socket(ZMQ.DEALER);
		requestServiceDealer.bind("inproc://workers");

		//TODO gateway support
		//TODO broadcast support (especially all connected children nodes)
		//TODO multicast support
		//TODO strip routing signature from request before forwarding
		//TODO keep-alive ping
	}

	public void start() {
		executor = Executors.newCachedThreadPool();
		executor.submit(new MainLoop());
		sendLoop = new SendLoop();
		executor.submit(sendLoop);

		System.out.printf("creating %d workers\n", numWorkers);
		replyServiceWorkers = new ZMQ.Socket[numWorkers];
		for (int i = 0; i < numWorkers; i++) {
			ZMQ.Socket replyServiceWorker = context.socket(ZMQ.REP);
			replyServiceWorker.connect("inproc://workers");
			replyServiceWorkers[i] = replyServiceWorker;
			executor.submit(new WorkerLoop(replyServiceWorker, i));
		}
	}

	public void stop() {
		executor.shutdown();
	}

	private class MainLoop implements Runnable {

		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					//  Initialize poll set
					ZMQ.Poller items = context.poller(2);
					int routerPollIndex = items.register(requestServiceRouter, ZMQ.Poller.POLLIN);
					int dealerPollIndex = items.register(requestServiceDealer, ZMQ.Poller.POLLIN);
					
					boolean more = false;
			        byte[] message;
			        
			        //  Switch messages between sockets
			        while (!Thread.currentThread().isInterrupted()) {            
			            //  poll and memorize multipart detection
			            items.poll(1000);

			            if (items.pollin(routerPollIndex)) {
			                while (true) {
			                    // receive message
			                    message = requestServiceRouter.recv(0);
			                    more = requestServiceRouter.hasReceiveMore();

			                    // Broker it
			                    requestServiceDealer.send(message, more ? ZMQ.SNDMORE : 0);
			                    if(!more){
			                        break;
			                    }
			                }
			            }
			            if (items.pollin(dealerPollIndex)) {
			                while (true) {
			                    // receive message
			                    message = requestServiceDealer.recv(0);
			                    more = requestServiceDealer.hasReceiveMore();
			                    // Broker it
			                    requestServiceRouter.send(message,  more ? ZMQ.SNDMORE : 0);
			                    if(!more){
			                        break;
			                    }
			                }
			            }
			        }
			        //  We never get here but clean up anyhow
			        requestServiceRouter.close();
			        requestServiceDealer.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	private class WorkerLoop implements Runnable {
		ZMQ.Socket workerSocket;
		@SuppressWarnings("unused") int id;
		WorkerLoop(ZMQ.Socket workerSocket, int id) {
			this.workerSocket = workerSocket;
			this.id = id;
		}

		public void run() {
			OtspMessage.Builder builder = OtspMessage.newBuilder();
			OtspMessage message = null;
			while (!Thread.currentThread().isInterrupted()) {
				try {
					byte[] request;

					request = workerSocket.recv(0);
					//System.out.printf("%d: received %d byte message\n", id, request.length);
					//  In order to display the 0-terminated string as a String,
					//  we omit the last byte from request
					
					//OtspMessage message = OtspMessage.parseFrom(ByteString.copyFrom(request, 0, request.length - 1), registry);
					message = builder.clear().mergeFrom(ByteString.copyFrom(request, 0, request.length - 1), registry).build();
					
					byte[] responseData = null;

					if (message.hasFrom() && message.hasTo()) {
						responseData = handleNamedMessage(request, message);
					} else {
						responseData = handleAnonymousMessage(message);
					}

					if (null == responseData) {
						responseData = new byte[1];
						responseData[0] = 0x00;
					}

					ByteBuffer responseBuffer = ByteBuffer.allocate(responseData.length + 1);
					responseBuffer.put(responseData);
					responseBuffer.put((byte) 0);
					//  Send reply back to client
					//  We will send a 0-terminated string (C string) back to the client,
					//  so that this server also works with The Guide's C and C++ "Hello World" clients
					workerSocket.send(responseBuffer.array(), 0);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			workerSocket.close();
		}
	}
	
	private class SendLoop implements Runnable {
		private class BufferPair {
			byte[] envelope; byte[] message;
			private BufferPair(byte[] envelope, byte[] message) { this.envelope = envelope; this.message = message; }
		}
		private LinkedBlockingQueue<BufferPair> sendQueue = new LinkedBlockingQueue<BufferPair>();
		public boolean send(byte[] envelope, byte[] message) {
			try {
				sendQueue.put(new BufferPair(envelope, message));
				return true;
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return false;
			}
		}

		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					BufferPair buffers = sendQueue.take();
					publishService.send(buffers.envelope, ZMQ.SNDMORE);
					publishService.send(buffers.message, 0);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	private byte[] handleNamedMessage(byte[] request, OtspMessage message) {
		if (message.hasExtension(OtspRouting.connectionManagement)) {
			return handleRouterMessage(message);
		}
		OtspNodeAddress fromNodeAddress = message.getFrom();
		OtspNodeAddress toNodeAddress = message.getTo();
		if (!fromNodeAddress.hasAddress() || !toNodeAddress.hasAddress()) {
			return null; //TODO make error case
		}
		NodeInfo messageFrom = ClientPool.getClient(fromNodeAddress.getAddress().asReadOnlyByteBuffer().getLong());
		NodeInfo messageTo = ClientPool.getClient(toNodeAddress.getAddress().asReadOnlyByteBuffer().getLong());

		if (null == messageTo || null == messageFrom) {
			return null; //TODO error
		}

		OtspMessage.Builder responseMessage = OtspMessage.newBuilder()
				.setFrom(getBoundNetworkNodeAddress())
				.setTo(messageFrom.getOtspNodeAddress())
				.setId(message.getId());
		
		if (!getMyNetworkAddressLong().equals((messageTo.getBindAddress() << 32))) {
			//TODO forward to gateway router
			System.out.println("dumping packet: no route to node");
			OtspRouting.ControlManagement controlMessage = OtspRouting.ControlManagement.newBuilder()
					.setType(OtspRouting.ControlManagement.Type.DESTINATION_UNREACHABLE)
					.setCode(OtspRouting.ControlManagement.Code.NODE_UNREACHABLE).build();
			responseMessage.setExtension(OtspRouting.controlManagement, controlMessage);
			return responseMessage.build().toByteArray();
		}

		if (!EncryptionService.checkMessageSignature(message, messageFrom)) {
			System.out.println("dumping packet: invalid signature");
			OtspRouting.ControlManagement controlMessage = OtspRouting.ControlManagement.newBuilder()
					.setType(OtspRouting.ControlManagement.Type.PARAMETER_PROBLEM)
					.setCode(OtspRouting.ControlManagement.Code.SOURCE_SIGNATURE_FAILED).build();
			responseMessage.setExtension(OtspRouting.controlManagement, controlMessage);
			return responseMessage.build().toByteArray();
		}

		ByteBuffer destinationEnvelopeBuffer = ByteBuffer.allocate(messageTo.getNetworkId().length+1);
		destinationEnvelopeBuffer.put(messageTo.getNetworkId());
		destinationEnvelopeBuffer.put((byte) 0);
		
		sendLoop.send(destinationEnvelopeBuffer.array(), EncryptionService.encrypt(request, messageTo));

		return responseMessage.build().toByteArray();
	}

	private byte[] handleRouterMessage(OtspMessage message) {
		OtspNodeAddress fromAddress = message.getFrom();
		if (message.hasExtension(OtspRouting.connectionManagement)) {
			//If the message had addressing, this is probably a disconnect. In fact, that's the only thing we know how to do.
			OtspRouting.ConnectionManagement connectionMessage = message.getExtension(OtspRouting.connectionManagement);
			if (connectionMessage.getOpcode().equals(ConnectionManagement.OpCode.BYE) && null != fromAddress) {
				ClientPool.evictNode(fromAddress.getAddress().asReadOnlyByteBuffer().getLong());
			}
		} else if (message.hasExtension(OtspRouting.groupManagement)) {
			OtspRouting.GroupManagement groupManagementMessage = message.getExtension(OtspRouting.groupManagement);
			//see MulticastService
		}
		
		return null;
	}

	private byte[] handleAnonymousMessage(OtspMessage message) {
		if (!message.hasExtension(OtspRouting.connectionManagement)) {
			return null; // TODO: error
		}

		OtspRouting.ConnectionManagement connectionManagementMessage = message.getExtension(OtspRouting.connectionManagement);
		DHExchangeGroup requestGroup = DHExchangeGroup.fromConnectionManagementMessage(connectionManagementMessage);
		DHExchangeGroup responseGroup = DHExchangeGroup.generateResponse(requestGroup);
		NodeInfo client = ClientPool.allocateNewClient();
		client.setSharedSecret(responseGroup.getSharedSecret());
		System.out.println("Setting up new client poolsize=" + ClientPool.getOccupiedCount());

		OtspMessage response = OtspMessage.newBuilder()
				.setFrom(getBoundNetworkNodeAddress())
				.setTo(client.getOtspNodeAddress())
				.setExtension(OtspRouting.connectionManagement, responseGroup.toConnectionManagementMessage())
				.build();

		return response.toByteArray();
	}

	private static Long getMyNetworkAddressLong() {
		return 9151314447111815168L;
	}

	private static OtspNodeAddress getBoundNetworkNodeAddress() {
		long localhostRouterAddress = 9151314447111815168L; //127.0.0.1.0.0.0.0
		ByteBuffer addressBuffer = ByteBuffer.allocate(8);
		addressBuffer.putLong(localhostRouterAddress);
		OtspNodeAddress address = OtspNodeAddress.newBuilder()
				.setAddress(ByteString.copyFrom(addressBuffer.array()))
				.build();
		return address;
	}

	public static void main( String[] args ) throws InvalidProtocolBufferException
	{
		PrimeCache.getPrime();
		int numProcessors = Runtime.getRuntime().availableProcessors() * 2;
		if (args.length > 0) {
			numProcessors = Integer.parseInt(args[0]);
		}
		TumorRouter server = new TumorRouter(numProcessors);
		server.start();
	}

}
