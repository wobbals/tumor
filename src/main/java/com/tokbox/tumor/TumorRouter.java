package com.tokbox.tumor;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.InvalidProtocolBufferException;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
import com.tokbox.tumor.proto.OtspCore.OtspNodeAddress;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.security.DHExchangeGroup;
import com.tokbox.tumor.security.EncryptionService;

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

		//TODO router-dealer the service, create reps on each thread (or poll?)
		//TODO recycle proto message objects
		//TODO multicast support (srsly do this first)
		//TODO strip routing signature from request before forwarding
	}

	public void start() {
		executor = Executors.newCachedThreadPool();
		executor.submit(new MainLoop());

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
			            items.poll();

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
			while (!Thread.currentThread().isInterrupted()) {
				try {
					byte[] request;

					request = workerSocket.recv(0);
					//System.out.printf("%d: received %d byte message\n", id, request.length);
					//  In order to display the 0-terminated string as a String,
					//  we omit the last byte from request
					ExtensionRegistry registry = ExtensionRegistry.newInstance();
					registry.add(OtspRouting.connectionManagement);
					registry.add(OtspRouting.signature);
					OtspMessage message = OtspMessage.parseFrom(ByteString.copyFrom(request, 0, request.length - 1), registry);

					byte[] responseData = null;

					if (message.hasFrom() && message.hasTo()) {
						responseData = handleNamedMessage(request, message);
					} else if (message.hasTo()) {
						responseData = handleRouterMessage(message);
					} else {
						responseData = handleAnonymousMessage(message);
					}

					if (null == responseData) {
						responseData = new byte[1];
						responseData[0] = 1;
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

	public static void main( String[] args ) throws InvalidProtocolBufferException
	{
		int numProcessors = Runtime.getRuntime().availableProcessors();
		if (args.length > 0) {
			numProcessors = Integer.parseInt(args[0]);
		}
		TumorRouter server = new TumorRouter(numProcessors);
		server.start();
	}

	public byte[] handleNamedMessage(byte[] request, OtspMessage message) {
		OtspNodeAddress fromNodeAddress = message.getFrom();
		OtspNodeAddress toNodeAddress = message.getTo();
		if (!fromNodeAddress.hasAddress() || !toNodeAddress.hasAddress()) {
			return null; //TODO make error case
		}
		RoutableNode messageFrom = ClientPool.getClient(fromNodeAddress.getAddress().asReadOnlyByteBuffer().getLong());
		RoutableNode messageTo = ClientPool.getClient(toNodeAddress.getAddress().asReadOnlyByteBuffer().getLong());

		if (null == messageTo || null == messageFrom) {
			return null; //TODO error
		}

		if (!getMyNetworkAddressLong().equals((messageTo.getBindAddress() << 32))) {
			//TODO forward to gateway router
			System.out.println("dumping packet: no route to node");
			return null; //control message: no route to host
		}

		if (!EncryptionService.checkMessageSignature(message, messageFrom)) {
			System.out.println("dumping packet: invalid signature");
			return null;
		}

		ByteBuffer destinationEnvelopeBuffer = ByteBuffer.allocate(messageTo.getNetworkId().length+1);
		destinationEnvelopeBuffer.put(messageTo.getNetworkId());
		destinationEnvelopeBuffer.put((byte) 0);
		
		synchronized(publishService) {
			publishService.send(destinationEnvelopeBuffer.array(), ZMQ.SNDMORE);
			publishService.send(EncryptionService.encrypt(request, messageTo), 0);
		}
		
		OtspMessage responseMessage = OtspMessage.newBuilder()
				.setFrom(getBoundNetworkNodeAddress())
				.setTo(messageFrom.getOtspNodeAddress())
				.setId(message.getId())
				.build();

		return responseMessage.toByteArray();
	}

	public byte[] handleRouterMessage(OtspMessage message) {
		// TODO Auto-generated method stub
		return null;
	}

	public byte[] handleAnonymousMessage(OtspMessage message) {
		if (!message.hasExtension(OtspRouting.connectionManagement)) {
			return null; // TODO: error
		}

		OtspRouting.ConnectionManagement connectionManagementMessage = message.getExtension(OtspRouting.connectionManagement);
		DHExchangeGroup requestGroup = DHExchangeGroup.fromConnectionManagementMessage(connectionManagementMessage);
		DHExchangeGroup responseGroup = DHExchangeGroup.generateResponse(requestGroup);
		RoutableNode client = ClientPool.allocateNewClient();
		client.setSharedSecret(responseGroup.getSharedSecret());
		System.out.println("Setting up new client");

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

}
