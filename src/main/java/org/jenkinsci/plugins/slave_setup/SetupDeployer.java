package org.jenkinsci.plugins.slave_setup;

import hudson.*;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.tasks.Shell;
import hudson.util.LogTaskListener;
import hudson.model.Queue.BuildableItem;
import hudson.model.Queue.Item;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes a deployment to all or a single node of the given fileset and executes the command line.
 *
 * @author Frederik Fromm
 */
public class SetupDeployer {
    /**
     * the logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SetupDeployer.class.getName());

    /**
     * Returns a list of all active slaves connected to the master.
     *
     * @return a list of all active slaves connected to the master
     */
    public List<Computer> getAllActiveSlaves() {
        final List<Computer> computers = Arrays.asList(Jenkins.getInstance().getComputers());

        List<Computer> activeComputers = new ArrayList<Computer>();

        for (Computer computer : computers) {
            if (!(computer instanceof Jenkins.MasterComputer) && computer.isOnline()) {
                activeComputers.add(computer);
            }
        }

        return activeComputers;
    }

    /**
     * @param c        the computer to upload th files to
     * @param root     the computer's target directory
     * @param listener the listener for logging etc.
     * @param config   the SetupConfig object containing the all SetupConfigItems
     * @throws IOException
     * @throws InterruptedException
     */
    public void deployToComputer(Computer c, FilePath root, TaskListener listener, SetupConfig config) throws IOException, InterruptedException {
        List<SetupConfigItem> setupConfigItems = config.getSetupConfigItems();

        for (SetupConfigItem setupConfigItem : setupConfigItems) {
            deployToComputer(c, root, listener, setupConfigItem);
        }
    }

    /**
     * @param c               the computer to upload th files to
     * @param root            the computer's target directory
     * @param listener        the listener for logging etc.
     * @param setupConfigItem the SetupConfigItem object containing the source dir and the command line
     * @throws IOException
     * @throws InterruptedException
     */
    public void deployToComputer(Computer c, FilePath root, TaskListener listener, SetupConfigItem setupConfigItem) throws IOException, InterruptedException {
        // do not deploy is prepare script for this setup config item did not work
        if (!setupConfigItem.isPrepareScriptExecuted()) {
            listener.getLogger().println("Slave NOT set up as prepare script was not executed successfully.");
            return;
        }

        //do not deploy if label of computer and config do not match.
        if (checkLabels(c, setupConfigItem)) {
            // copy files
            File sd = setupConfigItem.getFilesDir();
            File copyFlag = new File(root.getRemote() + "/copy.flag");
            if (sd != null && StringUtils.isNotBlank(sd.getPath())) {                
                if (copyFlag.exists()) {
                	listener.getLogger().println("Skipping copy, copy.flag indication was found" + copyFlag);
                } else {
                	listener.getLogger().println("Copying setup script files from " + sd);
                	new FilePath(sd).copyRecursiveTo(root);
                }                                
            }

            // execute command line
            String cmdLine = setupConfigItem.getCommandLine();
            listener.getLogger().println("Prepare to run after copy script ");
            executeScript(c.getNode(), root, listener, cmdLine, createEnvVarsForComputer(c, false));
        } else {
            listener.getLogger().println("Slave " + c.getName() + " NOT set up as assigned label expression '" + setupConfigItem.getAssignedLabelString() + "' does not match with node label '" + c.getNode().getLabelString() + "'");
        }
    }

    /**
     * Checks if this script should be executed on the target computer. If the computer is not set (on save of the
     * jenkins configuration page) or if the label expression of the config matches with the given
     * computer.
     */
    private boolean checkLabelsForComputerOrNull(Computer c, SetupConfigItem item) {
        return c == null || checkLabels(c, item);
    }

    /**
     * Returns true if the given setup config item is responsible for the given slave computer.
     *
     * @param c               the slave computer
     * @param setupConfigItem the setup config item to check
     * @return true if the given setup config item is responsible for the given slave computer
     */
    public boolean checkLabels(Computer c, SetupConfigItem setupConfigItem) {
        String assignedLabel = setupConfigItem.getAssignedLabelString();
        if (StringUtils.isBlank(assignedLabel)) {
            return true;
        }

        //Label l = Jenkins.getInstance().getLabel(setupConfigItem.getAssignedLabelString());
        Label label = Label.get(assignedLabel);

        return label.contains(c.getNode());
    }

    private void executeScript(Node node, FilePath root, TaskListener listener, String cmdLine, EnvVars additionalEnvironment) throws IOException, InterruptedException {
    	listener.getLogger().println(StringUtils.isNotBlank(cmdLine) ? "not prepare script" : "prepare script is not emtpy");
        if (StringUtils.isNotBlank(cmdLine)) {
            String nodeName = node.getNodeName();
            listener.getLogger().println("Executing script '" + cmdLine + "' on " + (StringUtils.isEmpty(nodeName) ? "master" : nodeName));
            Launcher launcher = root.createLauncher(listener);
            Shell s = new Shell(cmdLine);
            FilePath script = s.createScriptFile(root);
            int r = launcher.launch().cmds(s.buildCommandLine(script)).envs(getEnvironment(node, additionalEnvironment)).stdout(listener).pwd(root).join();

            if (r != 0) {
                listener.getLogger().println("script failed!");
                throw new AbortException("script failed!");
            }

            listener.getLogger().println("script executed successfully.");
        }
    }

    void setMachineIp(Computer c, FilePath root, TaskListener listener) throws IOException, InterruptedException {    	        
    	Node node = c.getNode();
    	String nodeName = node.getNodeName();
        String machineIp = "";
        String cmdLine = "ifconfig | grep \"inet addr:\" | head -1 | awk '{ print $2 }' | awk -F \":\" '{ print $2 }' > IP";
        Launcher launcher = root.createLauncher(listener);        
        Shell s = new Shell(cmdLine);
        FilePath script = s.createScriptFile(root);
        int r = launcher.launch().cmds(s.buildCommandLine(script)).stdout(listener).pwd(root).join();        

        if (r != 0) {
            listener.getLogger().println("IP extraction failed!");
            throw new AbortException("IP extraction failed! failed!");
        }
        
        new FilePath(root, "IP"); 
        machineIp = (new FilePath(root, "IP")).readToString();
        listener.getLogger().println("Get node "  + (StringUtils.isEmpty(nodeName) ? "master" : nodeName) + " IP : " + machineIp);       
        
/*    	EnvVars additionalEnvironment = new EnvVars();   	   	
    	additionalEnvironment.put("IP", machineIp);*/
    	
    	c.getEnvironment().override("IP", machineIp);        
    }
    
    /**
     * @param computerList    the list of computers to upload the setup files and execute command line
     * @param setupConfigItem the SetupConfigItem object
     */
    public void deployToComputers(List<Computer> computerList, SetupConfigItem setupConfigItem) {
        for (Computer computer : computerList) {
            try {
                FilePath root = computer.getNode().getRootPath();
                LogTaskListener listener = new LogTaskListener(LOGGER, Level.ALL);
                deployToComputer(computer, root, listener, setupConfigItem);
            } catch (IOException e) {
                LOGGER.severe(e.getMessage());
            } catch (InterruptedException e) {
                LOGGER.severe(e.getMessage());
            }
        }
    }

    /**
     * @param computerList the list of computers to upload the setup files and execute command line
     * @param config       the SetupConfig object
     */
    public void deployToComputers(List<Computer> computerList, SetupConfig config) {
        for (Computer computer : computerList) {
            try {
                FilePath root = computer.getNode().getRootPath();
                LogTaskListener listener = new LogTaskListener(LOGGER, Level.ALL);
                deployToComputer(computer, root, listener, config);
            } catch (IOException e) {
                LOGGER.severe(e.getMessage());
            } catch (InterruptedException e) {
                LOGGER.severe(e.getMessage());
            }
        }
    }

    public void executePrepareScripts(Computer c, SetupConfig config, TaskListener listener) {
        for (SetupConfigItem setupConfigItem : config.getSetupConfigItems()) {
            if (StringUtils.isBlank(setupConfigItem.getPrepareScript())) {
                setupConfigItem.setPrepareScriptExecuted(true);
            } else if (checkLabelsForComputerOrNull(c, setupConfigItem)) {
                boolean successful = executeScriptOnMaster(
                        setupConfigItem.getPrepareScript(),
                        c,
                        listener);
                setupConfigItem.setPrepareScriptExecuted(successful);

            }
        }
    }

    public void setComputerIpToChannelProperty(Computer c, TaskListener listener) {       

    }
        
    
    public void executePreLaunchScripts(Computer c, SetupConfig config, TaskListener listener) throws AbortException {
        for (SetupConfigItem setupConfigItem : config.getSetupConfigItems()) {
            if (!StringUtils.isBlank(setupConfigItem.getPreLaunchScript()) && checkLabels(c, setupConfigItem)) {
                boolean successful = executeScriptOnMaster(
                        setupConfigItem.getPreLaunchScript(),
                        c,
                        listener);
                if (!successful) {
                    throw new AbortException("pre-launch script not executed successfully");
                }
            }
        }
    }

    private boolean executeScriptOnMaster(String script, Computer c, TaskListener listener) {
        // execute scripts on master relative to jenkins install dir
    	boolean scriptExecuted = false;
    	Node node = Jenkins.getInstance();
        FilePath filePath = node.getRootPath();
        
        if (c==null) { return true;}
        
        try {
            executeScript(node, filePath, listener, script, createEnvVarsForComputer(c, true));
            scriptExecuted = true;
        } catch (Exception e) {
            listener.getLogger().println("script failed with exception: " + e.getMessage());
            scriptExecuted = false;
        }

        return scriptExecuted;
    }

    private EnvVars createEnvVarsForComputer(Computer c, boolean setBuildEnv) {
        EnvVars additionalEnvironment = new EnvVars();
        Map<String, String> buildVarMap = new HashMap();
        if (c != null) {
            additionalEnvironment.put("NODE_TO_SETUP_NAME", c.getName());
            Node node = c.getNode();
            if (node != null) {
                additionalEnvironment.put("NODE_TO_SETUP_LABELS", Util.join(node.getAssignedLabels(), " "));
            }
            if (!setBuildEnv) {
            	return additionalEnvironment;
            }
            // add all build parameters           
            Run B = c.getBuilds().getLastBuild();
            try {
            	//trial for getting latest build params
				//EnvVars buildVars = B.getEnvironment(null);
				//buildVarMap = buildVars.descendingMap();
	            //additionalEnvironment.put("IMAGE_ID", "nulli");
            	//additionalEnvironment.putAll(buildVarMap);
				
				//test for queue item retrieve params
	            List<BuildableItem> proj = Queue.getInstance().getBuildableItems(c);
	            String params = proj.get(0).getParams();
	            String[] splittedParams = params.split("\\s");
	            String[] getParam;
	            for (String param : splittedParams) {
	            	getParam = param.split("=");
	            	if (getParam.length < 2) {
	            		continue;
	            	}
	            	additionalEnvironment.put(getParam[0].trim().toUpperCase(), getParam[1].trim());
				}
	            //additionalEnvironment.put("IMAGE_ID", params);
	            
            }
            finally {
            	
            }
/*			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
*/
            
        }
        return additionalEnvironment;
    }

    /**
     * Returns the environment variables for the given node.
     *
     * @param node node to get the environment variables from
     * @param additionalEnvironment environment added to the environment from the node. Take precedence over environment from the node.
     * @return the environment variables for the given node
     */
    private EnvVars getEnvironment(Node node, EnvVars additionalEnvironment) {
        EnvVars envVars = new EnvVars();
        EnvironmentVariablesNodeProperty env = node.getNodeProperties().get(EnvironmentVariablesNodeProperty.class);
        if (env != null) {
            envVars.putAll(env.getEnvVars());
        }
        envVars.putAll(additionalEnvironment);
        return envVars;
    }


}
