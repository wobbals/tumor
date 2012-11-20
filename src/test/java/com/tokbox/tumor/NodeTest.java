package com.tokbox.tumor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import com.google.protobuf.InvalidProtocolBufferException;

/**
 * Unit test for simple App.
 */
public class NodeTest 
    extends TestCase
{
    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public NodeTest( String testName )
    {
        super( testName );
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite()
    {
//    	PrimeCache.getPrime();
//		int numProcessors = Runtime.getRuntime().availableProcessors() * 2;
//		TumorRouter server = new TumorRouter(numProcessors);
//		server.start();
		
        return new TestSuite( NodeTest.class );
    }

    @Override
    protected void setUp() {
		
    }
    
    public void testUnicastBenchmarkConcurrent() throws InterruptedException, ExecutionException
    {
    	int numConcurrent = 3;
    	ScheduledExecutorService executor = Executors.newScheduledThreadPool(numConcurrent+1);
    	Future<?> futures[] = new Future<?>[numConcurrent];
    	for (int i = 0; i < numConcurrent; i++) {
    		futures[i] = executor.schedule(new Runnable() {
    			public void run() {
    				try {
    					new UnicastBenchmark(8,50000).runUnicastBenchmark();
    				} catch (InvalidProtocolBufferException e) {
    					// TODO Auto-generated catch block
    					e.printStackTrace();
    				} catch (InterruptedException e) {
    					// TODO Auto-generated catch block
    					e.printStackTrace();
    				}
    			}}, i, TimeUnit.SECONDS);
    	}
    	for (Future<?> future : futures) {
    		future.get();
    	}
    }
    
    public void testUnicastBenchmarkSerial() {
    	try {
			new UnicastBenchmark(8,80000).runUnicastBenchmark();
			new UnicastBenchmark(8,80000).runUnicastBenchmark();
			new UnicastBenchmark(8,80000).runUnicastBenchmark();
		} catch (InvalidProtocolBufferException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
    
}
