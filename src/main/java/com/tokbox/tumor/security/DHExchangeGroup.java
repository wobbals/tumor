package com.tokbox.tumor.security;

import java.math.BigInteger;

import com.google.protobuf.ByteString;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.proto.OtspRouting.ConnectionManagement;

/**
 * Represents a pair of communicating agents that wish to establish a mutually shared secret.
 * The workflow is simple: One peer reaches out to the other with a handshake generated by
 * {@link DHExchangeGroup#generateHandshake()}. Upon receipt, the other peer can produce a
 * satisfactory response with {@link DHExchangeGroup#generateResponse(DHExchangeGroup)}, providing
 * the handshake from the first peer. After the first peer consumes the second peer's response
 * with {@link DHExchangeGroup#consumeResponse(ConnectionManagement)}, both instances of 
 * {@code DHExchangeGroup} will have a shared secret which is difficult to recover simply by
 * sniffing the data sent between them. 
 * 
 * TODO: Protect against replay
 * TODO: Support n-way shared secrets
 * @author charley
 *
 */
public class DHExchangeGroup {
	//TODO: Move these values over to the RFC for D-H exchange primes, optimized for some sort of mathy shit
	private static final BigInteger myP = PrimeCache.getPrime();
	private static final BigInteger myG = PrimeCache.getPrime();

	/**
	 * Step one in a D-H exchange is to call this function and kick off the exchange.
	 * @return
	 */
	public static DHExchangeGroup generateHandshake() {
		DHExchangeGroup group = new DHExchangeGroup();
		BigInteger p = myP;
		BigInteger g = myG;
		BigInteger secret = PrimeCache.getPrime();
		group.setP(p);
		group.setG(g);
		group.setSecret(secret);
		BigInteger q = g.modPow(secret, p);
		group.setQ(q);
		return group;
	}

	/**
	 * Responds to an instance created by #generateHandshake.
	 * @param exchangeGroup An offered D-H handshake.
	 * @return An instance (with initialized shared secret) that may be later 
	 * sent with {@link DHExchangeGroup#toConnectionManagementMessage()}.
	 */
	public static DHExchangeGroup generateResponse(DHExchangeGroup exchangeGroup) {
		BigInteger p = exchangeGroup.getP();
		BigInteger g = exchangeGroup.getG();
		BigInteger secret = PrimeCache.getPrime();
		BigInteger responseQ = g.modPow(secret, p);
		BigInteger sharedSecret = exchangeGroup.getQ().modPow(secret, p);
		DHExchangeGroup responseGroup = new DHExchangeGroup();
		responseGroup.setP(p);
		responseGroup.setG(g);
		responseGroup.setQ(responseQ);
		responseGroup.setSecret(secret);
		responseGroup.setSharedSecret(sharedSecret);
		return responseGroup;
	}

	/**
	 * Parses an exchange group from the appropriate OTSP Connection Management message extension.
	 * @param message
	 * @return
	 */
	public static DHExchangeGroup fromConnectionManagementMessage(OtspRouting.ConnectionManagement message) {
		DHExchangeGroup group = new DHExchangeGroup();
		BigInteger p = new BigInteger(message.getDhprime().toByteArray());
		BigInteger g = new BigInteger(message.getDhbase().toByteArray());
		BigInteger q = new BigInteger(message.getDhpublic().toByteArray());
		group.setP(p);
		group.setG(g);
		group.setQ(q);
		return group;
	}

	private BigInteger p;
	private BigInteger g;
	private BigInteger q;
	private BigInteger secret;
	private BigInteger sharedSecret;

	public DHExchangeGroup() {

	}

	/**
	 * Seeds a handshake with the response from a peer. After this function returns, this instance
	 * has a shared secret between the two peers.
	 * @param response
	 */
	public void consumeResponse(OtspRouting.ConnectionManagement response) {
		BigInteger responseQ = new BigInteger(response.getDhpublic().toByteArray());
		this.sharedSecret = responseQ.modPow(this.secret, this.p);
	}

	public OtspRouting.ConnectionManagement toConnectionManagementMessage() {
		OtspRouting.ConnectionManagement message = OtspRouting.ConnectionManagement.newBuilder()
				.setDhprime(ByteString.copyFrom(p.toByteArray()))
				.setDhbase(ByteString.copyFrom(g.toByteArray()))
				.setDhpublic(ByteString.copyFrom(q.toByteArray()))
				.setOpcode(ConnectionManagement.OpCode.CONNECT)
				.build();


		return message;
	}

	@Override
	public String toString() {
		StringBuffer output = new StringBuffer();
		output.append(String.format("p=%s\n", p));
		output.append(String.format("g=%s\n", g));
		output.append(String.format("q=%s\n", q));
		output.append(String.format("a=%s\n", secret));
		output.append(String.format("s=%s\n", sharedSecret));
		return output.toString();
	}

	public BigInteger getP() {
		return p;
	}

	public void setP(BigInteger p) {
		this.p = p;
	}

	public BigInteger getG() {
		return g;
	}

	public void setG(BigInteger g) {
		this.g = g;
	}

	public BigInteger getQ() {
		return q;
	}

	public void setQ(BigInteger q) {
		this.q = q;
	}

	public BigInteger getSecret() {
		return secret;
	}

	public void setSecret(BigInteger secret) {
		this.secret = secret;
	}

	public BigInteger getSharedSecret() {
		return sharedSecret;
	}

	public void setSharedSecret(BigInteger sharedSecret) {
		this.sharedSecret = sharedSecret;
	}
}
