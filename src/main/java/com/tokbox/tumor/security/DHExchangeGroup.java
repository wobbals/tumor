package com.tokbox.tumor.security;

import java.math.BigInteger;
import java.security.SecureRandom;

import com.google.protobuf.ByteString;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.proto.OtspRouting.ConnectionManagement;

public class DHExchangeGroup {
	private static final int PRIME_WIDTH = 1024;
	private static final int BASE_WIDTH = 1024;
	private static final int SECRET_WIDTH = 1024;
	private static final BigInteger myP = PrimeCache.getPrime();
	private static final BigInteger myG = PrimeCache.getPrime();
	
	public static DHExchangeGroup generateHandshake() {
		SecureRandom myRandom = new SecureRandom();

		DHExchangeGroup group = new DHExchangeGroup();
//		BigInteger p = BigInteger.probablePrime(PRIME_WIDTH, myRandom);
//		BigInteger g = BigInteger.probablePrime(BASE_WIDTH, myRandom);
//		BigInteger secret = BigInteger.probablePrime(SECRET_WIDTH, myRandom);
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
	
	public static DHExchangeGroup generateResponse(DHExchangeGroup exchangeGroup) {
		BigInteger p = exchangeGroup.getP();
		BigInteger g = exchangeGroup.getG();
//		SecureRandom myRandom = new SecureRandom();
//		BigInteger secret = BigInteger.probablePrime(SECRET_WIDTH, myRandom);
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
