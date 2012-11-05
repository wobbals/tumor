package com.tokbox.tumor.security;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;

import com.google.protobuf.ByteString;
import com.tokbox.tumor.RoutableNode;
import com.tokbox.tumor.proto.OtspCore.OtspMessageOrBuilder;
import com.tokbox.tumor.proto.OtspRouting;
import com.tokbox.tumor.proto.OtspCore.OtspMessage;
import com.tokbox.tumor.proto.OtspRouting.Signature;

public class EncryptionService {
	static {
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private static MessageDigest digest; 

	public static byte[] encrypt(byte[] bytes, RoutableNode node) {
		try {
			return node.getEncryptCipher().doFinal(bytes);
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	public static byte[] decrypt(byte[] bytes, RoutableNode node) {
		try {
			return node.getDecryptCipher().doFinal(bytes);
		} catch (IllegalBlockSizeException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BadPaddingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	public static boolean checkMessageSignature(OtspMessageOrBuilder message, RoutableNode node) {
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
	
	//TODO: salt?
	public static byte[] generateMessageSignature(OtspMessageOrBuilder message, RoutableNode node) {
		if (!message.hasId()) {
			return null;
		}
		ByteBuffer tokenBuffer = ByteBuffer.allocate(12);
		tokenBuffer.put(message.getFrom().getAddress().asReadOnlyByteBuffer());
		tokenBuffer.putInt(message.getId());
//		ByteBuffer tokenBuffer = ByteBuffer.allocate(4);
//		tokenBuffer.putInt(message.getId());
		byte[] token = tokenBuffer.array();
		byte[] hash;
		synchronized(digest) {
			digest.update(token);
			hash = digest.digest();
		}
		byte[] signature = encrypt(hash, node);
		return signature;
	}
	
	public static OtspMessage.Builder signMessage(OtspMessage.Builder messageBuilder, RoutableNode node) {
		if (!messageBuilder.hasId()) {
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
