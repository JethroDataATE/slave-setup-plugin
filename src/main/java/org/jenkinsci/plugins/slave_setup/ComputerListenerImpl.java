package org.jenkinsci.plugins.slave_setup;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.slaves.ComputerListener;

import java.io.IOException;
import java.util.List;

/**
 * @author Kohsuke Kawaguchi
 */
@Extension
public class ComputerListenerImpl extends ComputerListener {


    private SetupDeployer deployer = new SetupDeployer();

    @Override
    public void preLaunch(Computer c, TaskListener listener) throws IOException, InterruptedException, AbortException {
        listener.getLogger().println("just before slave " + c.getName() + " gets launched ...");

        SetupConfig config = SetupConfig.get();
    	if (c.getChannel()!=null) {    		
			try {
				listener.getLogger().println("Skip pre-launch scripts since Channel is available");
				if (c.getHostName() != null && !c.getHostName().isEmpty())					
					listener.getLogger().println("Detected host from Channel (probably JNLP) : " + c.getHostName());
			} catch (IOException e) {
				listener.getLogger().println("Cannot fetch slave host name from channel even though its available");
			}
    	} else {
            listener.getLogger().println("executing pre-launch scripts ...");
            deployer.executePreLaunchScripts(c, config, listener);
    	}
    }

    /**
     * Prepares the slave before it gets online by copying the given content in root and executing the configured setup script.
     * @param c the computer to set up
     * @param channel not used
     * @param root the root of the slave
     * @param listener log listener
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public void preOnline(Computer c, Channel channel, FilePath root, TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println("just before slave " + c.getName() + " gets online ...");

        SetupConfig config = SetupConfig.get();

        listener.getLogger().println("executing prepare script ...");
        deployer.executePrepareScripts(c, config, listener);
        deployer.setMachineIp(c, root, listener);
        

        listener.getLogger().println("setting up slave " + c.getName() + " ...");
        deployer.deployToComputer(c, root, listener, config);

        listener.getLogger().println("slave setup done.");
    }

}
