package com.tokbox.tumor.security;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

public class PrimeCache {
	private static ExecutorService executor;
	private static LinkedBlockingQueue<BigInteger> cache;
	private static SecureRandom myRandom;

	static {
		myRandom = new SecureRandom();
		cache = new LinkedBlockingQueue<BigInteger>(32);
		executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable);
				thread.setDaemon(true);
				thread.setName("primecache");
				return thread;
			}
		});
		executor.submit(new CacheWorker());
	}
	
	public static BigInteger getPrime() {
		try {
			return cache.take();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return getPrime();
		}
	}
	
	private static class CacheWorker implements Runnable {
		
		public void run() {
			while(!Thread.currentThread().isInterrupted()) {
				try {
					cache.put(BigInteger.probablePrime(1024, myRandom));
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
	}
}
