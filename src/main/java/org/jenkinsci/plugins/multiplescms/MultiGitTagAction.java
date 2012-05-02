package org.jenkinsci.plugins.multiplescms;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.eclipse.jgit.lib.ObjectId;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import com.ctc.wstx.io.BranchingReaderSource;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Describable;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.TaskAction;
import hudson.model.TaskListener;
import hudson.model.TaskThread;
import hudson.model.TaskThread.ListenerAndText;
import hudson.plugins.git.Branch;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitAPI;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTagAction;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.GitTagAction.TagInfo;
import hudson.plugins.git.GitTagAction.TagWorkerThread;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.remoting.VirtualChannel;
import hudson.scm.AbstractScmTagAction;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.util.CopyOnWriteMap;
import hudson.util.DescribableList;
import hudson.util.MultipartFormDataParser;
import hudson.util.StreamTaskListener;

public class MultiGitTagAction extends TaskAction implements
        Describable<MultiGitTagAction> {

    protected final AbstractBuild build;
    // TODO: remove
    public final String ws;
    // TODO: remove
    public final Map<String, Map<String, List<String>>> tags;
    private final DescribableList<SCM, Descriptor<SCM>> scms;
    
    public final List<GitSCM> gitSCMs = new ArrayList<GitSCM>();
    
    // TODO: remove
    public final Map<String, List<BranchSpec>> repositories;
    
    /**
     * This is initialized from the last build.
     *
     */
    public final Map<String, List<String>> repositoriesTags = new CopyOnWriteMap.Tree<String, List<String>>(); // TODO: remove
   
    // TODO: use the last build to update tags
    protected MultiGitTagAction(AbstractBuild build,
            DescribableList<SCM, Descriptor<SCM>> scms) {

        this.build = build;
        this.ws = build.getWorkspace().getRemote();

        this.scms = scms;
        
        tags = new CopyOnWriteMap.Tree<String, Map<String, List<String>>>();
        
        repositories = new HashMap<String, List<BranchSpec>>();
        for (SCM scm : scms) {
            if (scm instanceof GitSCM) {
                GitSCM git = (GitSCM) scm;
                gitSCMs.add(git);

                String scmName = git.getScmName();

                List<BranchSpec> branches = git.getBranches();
                repositories.put(scmName, branches);

                Map<String, List<String>> scmTags = new CopyOnWriteMap.Tree<String, List<String>>();
                for (BranchSpec b : branches) {
                	scmTags.put(b.getName(), new ArrayList<String>());
                }
                
                tags.put(scmName, scmTags);
                repositoriesTags.put(scmName, new ArrayList<String>());
                
                // TODO: now using the GitAPI let's try to identify the tags
            }
        }
        
        
    }
    
    public final ObjectId getBuildSha1(SCM scm) {
    	return ((GitSCM)scm).getBuildData(build, false).lastBuild.getSHA1();
    }
    
    public final String getUrlName() {
        // to make this consistent with CVSSCM, even though the name is bit off
        return "multitagBuild";
    }

    public String getDisplayName() {
        return "Multi-Git Tag";
    }

    /**
     * Returns true if the build is tagged already.
     */
    public boolean isTagged() {
        // TODO: implement this
        return false;
    }

    public String getIconFileName() {

        if (!isTagged() && !getACL().hasPermission(getPermission())) {
            return null;
        }

        return "save.gif";
    }

    public AbstractBuild getBuild() {
        return build;
    }

    /**
     * This message is shown as the tool tip of the build badge icon.
     */
    public String getTooltip() {
        return null;
    }

    public Descriptor<MultiGitTagAction> getDescriptor() {
        return Hudson.getInstance().getDescriptorOrDie(getClass());
    }

    public static class TagInfo {
    	ObjectId sha1;
    	Build build;
    	BuildData buildData;
    	SCM scm;

        private String module, tag;
    }

    protected ACL getACL() {
        return build.getACL();
    }

    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        req.getView(this, chooseAction()).forward(req, rsp);
    }

    @Override
    public Permission getPermission() {
        return GitSCM.TAG;
    }

    protected synchronized String chooseAction() {
        // TODO: should have an inProgress.jelly though

//		if (workerThread != null)
//			return "inProgress.jelly";
        return "multitagForm.jelly";
    }

    /**
     * Invoked to actually tag the workspace.
     */
    public synchronized void doSubmit(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        
    	getACL().checkPermission(getPermission());

        MultipartFormDataParser parser = new MultipartFormDataParser(req);

        Logger LOGGER = Logger.getLogger(MultiGitTagAction.class.getName());
        LOGGER.info("[DEBUG] !!! triggered tag !!! ");

        Map<String/*repo*/, Map<String/*branch*/, String/*Tag*/>> newTags = new HashMap<String, Map<String, String>>();

        int i = -1;
        for (Map.Entry<String, List<BranchSpec>> repo : this.repositories.entrySet()) {
            i++;

            Map<String/*branch*/, String/*Tag*/> branchTags = new HashMap<String, String>();

            int j = -1;
            for (BranchSpec b : repo.getValue()) {
                j++;

                if (repo.getValue().size() > 1 && parser.get("tag_" + i + "_" + j) == null) {
                    continue; // when tags.size()==1, UI won't show the checkbox.
                }
                branchTags.put(b.getName(), parser.get("name_" + i + "_" + j));
            }

            if (branchTags.size() > 0) {
                newTags.put(repo.getKey(), branchTags);
            }

        }

        String comment = parser.get("comment");

        new MultiTagWorkerThread(newTags, comment).start();

        rsp.sendRedirect(".");
    }

    /**
     * The thread that performs tagging operation asynchronously.
     */
    public final class MultiTagWorkerThread extends TaskThread {

        private final Map<String, Map<String, String>> tagSet;
        /**
         * If the user provided a separate credential, this object represents that.
         */
        private final String comment;

        public MultiTagWorkerThread(Map<String, Map<String, String>> tagSet, String comment) {
            super(MultiGitTagAction.this, ListenerAndText.forMemory());

            this.tagSet = tagSet;
            this.comment = comment;
        }

        @Override
        protected void perform(final TaskListener listener) throws Exception {

            Logger LOGGER = Logger.getLogger(MultiTagWorkerThread.class.getName());

            final EnvVars environment = build.getEnvironment(listener);

            // LOGGER.info("[DEBUG] environment: " + environment.toString());

            try {
                final FilePath workspace = new FilePath(new File(ws));

                LOGGER.info("[DEBUG] workspace: " + ws);

                Object returnData = workspace.act(new FilePath.FileCallable<Object[]>() {

                    private static final long serialVersionUID = 1L;
                    protected String tagName;

                    public Object[] invoke(File localWorkspace, VirtualChannel channel)
                            throws IOException {

                        Logger LOGGER = Logger.getLogger(MultiTagWorkerThread.class.getName());
                        
                        for (final String r : tagSet.keySet()) {
                            LOGGER.info("[DEBUG] repo: " + r);
                            final FilePath repository = new FilePath(new File(ws + '/' + r));
                            
                            for (final String b : tagSet.get(r).keySet()) {
                                LOGGER.info("[DEBUG] branch: " + b);

                                try {
	                                try {
	                                    IGitAPI git = new GitAPI("git", repository, listener, environment, null);
	                                    tagName = tagSet.get(r).get(b);
	                                    String buildNum = "hudson-" + build.getProject().getName() + "-" + tagName;
	
	                                    LOGGER.info("[DEBUG] git repository path: " + repository);
	
	                                    LOGGER.info("[DEBUG] git buildNum: " + buildNum);
	
	                                    if (git.hasGitRepo()) {
	                                        LOGGER.log(Level.INFO, "[DEBUG] path {0} is a git repository!", repository.getName());
	                                        git.tag(tagName, "Hudson Build #" + buildNum);
	                                    }
	
	                                    // add the current tag to the Action tags member, to be saved to build.xml. 
	                                    //  Next time you'll open the build you'll see the tags given.
//	                                    if (MultiGitTagAction.this.tags.get(r) == null)
//	                                    	MultiGitTagAction.this.tags.put(r, new HashMap<String, List<String>>());
//	                                    
//	                                    if (MultiGitTagAction.this.tags.get(r).get(b) == null)
//	                                    	MultiGitTagAction.this.tags.get(r).put(b, new ArrayList<String>());

	                                    MultiGitTagAction.this.tags.get(r).get(b).add(tagName);
	
	                                }  catch (GitException ex) {
	                                	LOGGER.log(Level.SEVERE, "Uncaught GIT exception", ex);
	                                	
	                                    // throw new IOException(ex.getMessage(), ex);
	                                	// trying next branch
	                                }
                                } catch (Exception unknownException) {
                                	LOGGER.log(Level.SEVERE, "Uncaught UNKNOWN exception", unknownException);
                                }
                            } // end for branches
                        } // end for repositories

                        return new Object[]{null, build};
                    }
                });

                // LOGGER.info("[DEBUG] returnData: " + returnData.getClass().getName());

                LOGGER.info("[DEBUG] trying saving the build");
                getBuild().save();
                workerThread = null;
            } catch (Exception ex) {
                LOGGER.info("[DEBUG] exception: " + ex.getMessage());
                ex.printStackTrace(listener.error("Error taggin repo: %s", ex.getMessage()));
                throw ex;
            }
        }
    }

    /**
     * Just for assisting form related stuff.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<MultiGitTagAction> {

        public String getDisplayName() {
            return "MultiTag";
        }
    }
}
