package org.jenkinsci.plugins.slave_setup;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.Future;
import hudson.remoting.RequestAbortedException;
import hudson.slaves.Messages;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.TimeUnit2;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import jenkins.model.Jenkins;


/**
 * @author Mosh
 */
@Extension
public class SlaveMonitor extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(SlaveMonitor.class.getName());

    private final Long recurrencePeriod;

    public SlaveMonitor() {
        super("alive slaves monitor");
        recurrencePeriod = Long.getLong("jenkins.slaves.checkAlivePeriod", TimeUnit.SECONDS.toMillis(10));
        LOGGER.log(Level.FINE, "check alive period is {0}ms", recurrencePeriod);
    }

    @Override
    public long getRecurrencePeriod() {          
         return enabled ? (recurrencePeriod > 10000 ? recurrencePeriod : 20000) : TimeUnit2.DAYS.toMillis(30);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
    	if (!enabled)   return;
        for (Computer computer : Jenkins.getInstance().getComputers()) {        	
            if (computer instanceof SlaveComputer && !computer.isOffline()) {
                final SlaveComputer checkedcomputer = (SlaveComputer) computer;
                try {
                    if (!isAlive(checkedcomputer)) {
                        LOGGER.info("Slave is dead: " + checkedcomputer.getNode().getNodeName());
                        //checkedcomputer.terminate();
                        //disconnectNode(checkedcomputer);
                        LOGGER.info("Slave Disonnection is done: " + checkedcomputer.getNode().getNodeName());
                    }
                } catch (Exception e) {
                    LOGGER.info("Slave is dead and failed to terminate: " + checkedcomputer.getNode().getNodeName() + "message: " + e.getMessage());
                    
                }
            }
        }
    }

    
    private boolean isAlive(SlaveComputer checkedcomputer) {
    	
    	LOGGER.info("Enter slave monitor is isAlive: " + checkedcomputer.getNode().getNodeName());    		
/*		try {
			//Connection connection = new Connection(checkedcomputer.getHostName(), port);
			
		} catch (IOException e) {				
		} catch (InterruptedException e) {			
			e.printStackTrace();
		}*/
    	if (checkedcomputer.getChannel() == null) {
    		LOGGER.info(getTimestamp() +"Slave Channel is closed:  " + checkedcomputer.getNode().getNodeName());
    		return false;
    	}    		
		//do not disconnect machine
		//checkedcomputer.tryReconnect();
		//checkedcomputer.getChannel().getLastHeard();    		    		
    	
    	LOGGER.info("Slave " + checkedcomputer.getNode().getNodeName() + "was last heard at " + checkedcomputer.getChannel().current().getLastHeard());
    	return false;
    }
    private void disconnectNode(SlaveComputer checkedSlave) {
        try {
        	checkedSlave.getChannel().close();        	
        	checkedSlave.disconnect(OfflineCause.create(Messages._ConnectionActivityMonitor_OfflineCause()));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to disconnect Channel of Node: " + checkedSlave.getNode().getNodeName());
        }
    }


    // disabled by default 
    public boolean enabled = Boolean.getBoolean(SlaveMonitor.class.getName()+".enabled");
    
    public int port = 22; 
    
    private void ping(Channel channel) throws IOException, InterruptedException {
        Future<?> f = channel.callAsync(new Ping());
        long start = System.currentTimeMillis();
        long end = start + recurrencePeriod - 200;

        long remaining;
        do {
            remaining = end-System.currentTimeMillis();
            try {
                f.get(Math.max(0,remaining),MILLISECONDS);
                return;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RequestAbortedException)
                    return; // connection has shut down orderly.               
            } catch (TimeoutException e) {
                throw new IOException("Ping started on "+start+" hasn't completed at "+System.currentTimeMillis());
            }
        } while(remaining>0);
    }


    private static final class Ping implements Callable<Void, IOException> {
        private static final long serialVersionUID = 1L;

        public Void call() throws IOException {
            return null;
        }
    }
    
    /**
     * Gets the formatted current time stamp.
     *
     * @return the formatted current time stamp.
     */
    @Restricted(NoExternalUse.class)
    public static String getTimestamp() {
        return String.format("[%1$tD %1$tT]", new Date());
    }
}
