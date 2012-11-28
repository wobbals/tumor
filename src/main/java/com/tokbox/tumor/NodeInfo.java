package com.tokbox.tumor;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

import com.google.protobuf.ByteString;
import com.tokbox.tumor.proto.OtspCore.OtspNodeAddress;
import com.tokbox.tumor.security.EncryptionService;

/**
 * Useful info about a node.
 * @author charley
 *
 */
public class NodeInfo {
	private static String TRANSPORT_CRYPTO_ALGORITHM = "AES";
	private Long networkId;
	private BigInteger sharedSecret;
	private SecretKeySpec keySpec;
	private Cipher decryptCipher;
	private Cipher encryptCipher;
	
	public NodeInfo(Long networkId) {
		this.networkId = networkId;
	}
	
	public byte[] getNetworkId() {
		ByteBuffer buf = ByteBuffer.allocate(8);
		buf.putLong(networkId);
		return buf.array();
	}
	
	public String getNetworkIdOctets() {
		return bytesToOctets(getNetworkId());
	}
	
	public Long getNetworkIdLong() {
		return networkId;
	}
	
	public Long getBindAddress() {
		return networkId >> 32;
	}
	
	public InetAddress getRouterAddress() {
		try {
			return InetAddress.getByName(intToOctets(getBindAddress()));
		} catch (UnknownHostException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	public Long getClientSerial() {
		return (0x00000000FFFFFFFFL & networkId);
	}
	
	@Override
	public int hashCode() {
		return networkId.hashCode();
	}
	
	@Override
	public String toString() {
		return String.format("netId=%s bindaddr=%s serial=%d", bytesToOctets(getNetworkId()),
				intToOctets(getBindAddress()), getClientSerial());
	}
	
	public static String bytesToOctets(byte[] bytes) {
		return bytesToOctets(bytes, bytes.length);
	}
	
	public static String bytesToOctets(byte[] bytes, int length) {
		StringBuffer outputString = new StringBuffer();
		for (int i = 0; i < length; i++) {
			byte someByte = bytes[i];
			outputString.append(String.format("%d.", 0xFF & someByte));
		}
		outputString.deleteCharAt(outputString.length() - 1);
		return outputString.toString();
	}
	
	public static String longToOctets(long someLong) {
		StringBuffer outputString = new StringBuffer();
		for (int shiftBytes = 7; shiftBytes >= 0 ; shiftBytes--) {
			long mask = 0x00000000000000FFL;
			outputString.append(String.format("%d.", (0xFF & (someLong & (long)(mask << shiftBytes*8)) >> (shiftBytes * 8) )));
		}
		outputString.deleteCharAt(outputString.length() - 1);
		return outputString.toString();
	}
	
	/**
	 * 
	 * @param someInt it's confusing, i know.
	 * @return
	 */
	private String intToOctets(long someInt) {
		StringBuffer outputString = new StringBuffer();
		for (int shiftBytes = 3; shiftBytes >= 0 ; shiftBytes--) {
			long mask = 0x00000000000000FFL;
			outputString.append(String.format("%d.", (someInt & (long)(mask << shiftBytes*8)) >> (shiftBytes * 8) ));
		}
		outputString.deleteCharAt(outputString.length() - 1);
		return outputString.toString();
	}
	
	public OtspNodeAddress getOtspNodeAddress() {
		ByteBuffer buffer = ByteBuffer.allocate(8);
		buffer.putLong(networkId);
		OtspNodeAddress address = OtspNodeAddress.newBuilder().setAddress(ByteString.copyFrom(buffer.array())).build();
		return address;
	}

	public BigInteger getSharedSecret() {
		return sharedSecret;
	}

	public Cipher getDecryptCipher() {
		return decryptCipher;
	}

	public Cipher getEncryptCipher() {
		return encryptCipher;
	}
	
	public void setSharedSecret(BigInteger sharedSecret) {
		this.sharedSecret = sharedSecret;
		try {
			MessageDigest digest = MessageDigest.getInstance(EncryptionService.HASH_ALGORITHM);
			digest.update(sharedSecret.toByteArray());
			byte[] secretHash = digest.digest();

			keySpec = new SecretKeySpec(secretHash, TRANSPORT_CRYPTO_ALGORITHM);
			decryptCipher = Cipher.getInstance(TRANSPORT_CRYPTO_ALGORITHM);
			decryptCipher.init(Cipher.DECRYPT_MODE, keySpec);
			encryptCipher = Cipher.getInstance(TRANSPORT_CRYPTO_ALGORITHM);
			encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec);
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidKeyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
