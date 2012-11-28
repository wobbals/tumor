package com.tokbox.tumor.security;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;

/**
 * PrimeCache runs a static-allocated daemon thread that generates prime numbers
 * of a fixed size. Found to be useful in handling bursty load for big primes.
 * @author charley
 *
 */
public class PrimeCache {
	private static int MAX_CACHE_SIZE = 32;
	private static int PRIME_LENGTH = 1024;
	private static ExecutorService executor;
	private static LinkedBlockingQueue<BigInteger> cache;
	private static SecureRandom myRandom;

	static {
		myRandom = new SecureRandom();
		cache = new LinkedBlockingQueue<BigInteger>(MAX_CACHE_SIZE);
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
	
	/**
	 * Will block if the cache is empty, until the daemon makes another
	 * prime.
	 * @return
	 */
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
		
		/**
		 * Using a blocking queue, a put to the full cache will
		 * block until {@link PrimeCache#getPrime()} is called elsewhere.
		 */
		public void run() {
			while(!Thread.currentThread().isInterrupted()) {
				try {
					cache.put(BigInteger.probablePrime(PRIME_LENGTH, myRandom));
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		
	}
}
