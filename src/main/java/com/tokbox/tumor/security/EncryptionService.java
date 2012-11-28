package com.tokbox.tumor.security;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import com.google.protobuf.ByteString;
import com.tokbox.tumor.NodeInfo;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
import com.tokbox.tumor.proto.OtspCore.OtspMessageOrBuilder;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.proto.OtspRouting.Signature;

/**
 * Utility methods for common encryption tasks.
 * @author charley
 *
 */
public class EncryptionService {
	public static String HASH_ALGORITHM = "SHA-256";
	static {
		try {
			digest = MessageDigest.getInstance(HASH_ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static MessageDigest digest; 

	/**
	 * Encrypts a payload using the cipher allocated to a node. Typically, this 
	 * cipher is keyed with a secret specific to the node.
	 * @param bytes An unencrypted payload
	 * @param node The node whose cipher+key we will use.
	 * @return The encrypted value for {@code bytes}
	 */
	public static byte[] encrypt(byte[] bytes, NodeInfo node) {
		try {
			Cipher cipher = node.getEncryptCipher();
			synchronized(cipher) {
				return cipher.doFinal(bytes);
			}
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * Inverse function of {@link EncryptionService#encrypt(byte[], NodeInfo)}.
	 * @param node The node whose cipher+key we will use.
	 * @param bytes The encrypted payload.
	 * @return The decrypted value for {@code bytes}.
	 */
	public static byte[] decrypt(NodeInfo node, byte[] bytes) {
		try {
			Cipher cipher = node.getDecryptCipher();
			synchronized (cipher) {
				return cipher.doFinal(bytes);
			}
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	/**
	 * Verifies a signature, presumably produced by {@link EncryptionService#generateMessageSignature(OtspMessageOrBuilder, NodeInfo)}
	 * @param message The message to verify.
	 * @param node The node this message was presumably signed by.
	 * @return {@code true} If the message seems to have been signed by {@code node}.
	 */
	public static boolean checkMessageSignature(OtspMessageOrBuilder message, NodeInfo node) {
		if (!message.hasExtension(OtspRouting.signature)) {
			System.out.println("no signature extension");
			return false;
		}
		byte[] generatedSignature = generateMessageSignature(message, node);
		ByteString messageSignatureByteString = message.getExtension(OtspRouting.signature).getSignature();
		if (null == messageSignatureByteString || messageSignatureByteString.size() != generatedSignature.length) {
			System.out.println("signature size mismatch");
			return false;
		}
		for (int i = 0; i < generatedSignature.length; i++) {
			if (generatedSignature[i] != messageSignatureByteString.byteAt(i)) {
				System.out.println("signature data mismatch");
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * Hashes a part of the message (ID and FromAddress) to a fixed size,
	 * encrypts that block using a cipher specific to the specified node.
	 * TODO: Salt and fix this function: the signature is too exposed and can be replayed. Try a counting-mode?
	 * @param message
	 * @param node
	 * @return An encrypted message signature
	 */
	public static byte[] generateMessageSignature(OtspMessageOrBuilder message, NodeInfo node) {
		if (!message.hasId()) {
			return null;
		}
		ByteBuffer tokenBuffer = ByteBuffer.allocate(32);
		tokenBuffer.put(message.getFrom().getAddress().asReadOnlyByteBuffer());
		tokenBuffer.put(message.getTo().getAddress().asReadOnlyByteBuffer());
		tokenBuffer.putInt(message.getId());
//		ByteBuffer tokenBuffer = ByteBuffer.allocate(4);
//		tokenBuffer.putInt(message.getId());
		byte[] token = tokenBuffer.array();
		byte[] hash;
		synchronized(digest) {
			digest.update(token, 0, tokenBuffer.position());
			hash = digest.digest();
		}
		byte[] signature = encrypt(hash, node);
		return signature;
	}
	
	/**
	 * Attaches a payload that is cryptographically signed using a secret negotiated
	 * between Node and its' router during connection time. Used to verify message authenticity.
	 * @param messageBuilder The message to be signed.
	 * @param node The node to sign the message.
	 * @return {@code messageBuilder}, with attached signature if the operation was successful.
	 */
	public static OtspMessage.Builder signMessage(OtspMessage.Builder messageBuilder, NodeInfo node) {
		if (!messageBuilder.hasId() || !messageBuilder.hasTo() || !messageBuilder.hasFrom()) {
			System.out.println("warning: unsignable message");
			return messageBuilder;
		}
		byte[] signature = generateMessageSignature(messageBuilder, node);
		Signature signatureExtension = Signature.newBuilder()
				.setSignature(ByteString.copyFrom(signature))
				.build();
		messageBuilder.setExtension(OtspRouting.signature, signatureExtension);
		return messageBuilder;
		
	}
}
