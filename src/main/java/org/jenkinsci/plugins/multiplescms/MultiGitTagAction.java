package org.jenkinsci.plugins.multiplescms;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;

import org.eclipse.jgit.lib.ObjectId;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Describable;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.TaskAction;
import hudson.model.TaskListener;
import hudson.model.TaskThread;
import hudson.plugins.git.GitAPI;
import hudson.plugins.git.GitException;
import hudson.plugins.git.Revision;
import hudson.plugins.git.GitSCM;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.util.CopyOnWriteMap;
import hudson.util.DescribableList;
import hudson.util.MultipartFormDataParser;
import java.io.*;

public class MultiGitTagAction extends TaskAction implements
        Describable<MultiGitTagAction> {

    /**
     * current build
     */
    protected final AbstractBuild<?, ?> build;
    /**
     * Current Tag being set
     */
    protected TagInfo currentTag = null;
    public final Writer result = new StringWriter();
    protected final PrintWriter printWriter = new PrintWriter(result);
    protected List<TagInfo> tags = new ArrayList<TagInfo>();
    protected Map<String, Set<TagInfo>> revisionTags = new CopyOnWriteMap.Tree<String, Set<TagInfo>>();
    /**
     * all the Git SCMs that may be tagged
     */
    public final List<GitSCM> gitSCMs = new ArrayList<GitSCM>();

    /**
     * Constructor
     *
     * @param build
     * @param scms
     */
    protected MultiGitTagAction(AbstractBuild<?, ?> build,
            DescribableList<SCM, Descriptor<SCM>> scms) {

        this.build = build;

        for (SCM scm : scms) {
            if (scm instanceof GitSCM) {
                gitSCMs.add((GitSCM) scm);
            }
        }
    }

    /**
     * Returns all tags (names) for specified revision
     *
     * @param revision
     * @return
     */
    public final List<String> getTagNames(String revision) {
        List<String> tagNames = new ArrayList<String>();

        Set<TagInfo> tagSet = revisionTags.get(revision);
        if (tagSet != null) {
            for (TagInfo tag : tagSet) {
                tagNames.add(tag.getName());
            }
        }

        return tagNames;
    }

    /**
     * Returns the SHA for the corresponding repository/branch/ and build's
     * commit. This SHA will be used to tag current build
     *
     * @param scm
     * @return
     */
    public final ObjectId getBuildSha1(SCM scm) {
        return ((GitSCM) scm).getBuildData(build, false).lastBuild.getSHA1();
    }

    public final String getUrlName() {
        return "multitagBuild";
    }

    /**
     * The Action Name that will be displayed on the left menu
     */
    public String getDisplayName() {
        if (tags.isEmpty()) {
            return "MultiGit Tag";
        }

        return "Tagged: " + ((tags.size() == 1) ? tags.get(0).getName() : "(multiple tags)");
    }

    /**
     * Returns true if the build is tagged already.
     */
    public boolean isTagged() {
        return !this.tags.isEmpty();
    }

    public String getIconFileName() {
        if (!isTagged() && !getACL().hasPermission(getPermission())) {
            return null;
        }

        return "/plugin/multiple-scms/icons/24x24/label.png";
    }

    public AbstractBuild<?, ?> getBuild() {
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

        TagInfo newTag = new TagInfo(parser.get("mt_tagname"), parser.get("comment"));
        if (newTag.isValid()) {
            this.tags.add(newTag);
            this.currentTag = newTag;
        }

        if (this.tags.contains(newTag.getName())) {
            throw new GitException("tag already exists");
        }

        new MultiTagWorkerThread().start();

        rsp.sendRedirect(".");
    }

    /**
     * The thread that performs tagging operation asynchronously.
     */
    public final class MultiTagWorkerThread extends TaskThread {

        public MultiTagWorkerThread() {
            super(MultiGitTagAction.this, ListenerAndText.forMemory());
        }

        /**
         *
         * @param scm
         * @param workspace
         * @param environment
         * @return
         */
        protected FilePath workingDirectory(final GitSCM scm, final FilePath workspace, EnvVars environment) {
            String relativeTargetDir = scm.getRelativeTargetDir();

            if (relativeTargetDir == null || relativeTargetDir.length() == 0 || relativeTargetDir.equals(".")) {
                return workspace;
            }

            return workspace.child(environment.expand(relativeTargetDir));
        }

        @Override
        protected void perform(final TaskListener listener) throws Exception {

            final EnvVars environment = build.getEnvironment(listener);

            final FilePath workspace = build.getWorkspace();

            for (final GitSCM scm : gitSCMs) {

                String scmName = scm.getScmName();

                Set<TagInfo> scmTags = revisionTags.get(scmName);
                if (scmTags == null) {
                    scmTags = new HashSet<TagInfo>();
                    revisionTags.put(scmName, scmTags);
                }

                // get the working directory for the current SCM being processed
                final FilePath workingDirectory = workingDirectory(scm, workspace, environment);

                if (!workingDirectory.exists())
                    throw new GitException("workingDirectory: " + workingDirectory.getRemote() + " does not exists.");

                try {
                    workingDirectory.act(new GitTagFileCallable(listener, workingDirectory, environment, getBuildSha1(scm), currentTag.getName(), currentTag.getComment()));

                    scmTags.add(currentTag);

                } catch (GitException ex) {
                    ex.printStackTrace(printWriter);

                    ex.printStackTrace(listener.error("Error taggin repo: %s", ex.getMessage()));
                    throw ex;
                } catch (IOException ex) {
                    ex.printStackTrace(printWriter);

                    ex.printStackTrace(listener.error("Error taggin repo: %s", ex.getMessage()));
                    throw ex;
                } catch (InterruptedException ex) {
                    ex.printStackTrace(printWriter);

                    ex.printStackTrace(listener.error("Error taggin repo: %s", ex.getMessage()));
                    throw ex;
                }

            } // end for

            getBuild().save();

            currentTag = null;
            workerThread = null;
        }
    }

    /**
     * Its purpose is to hold the tagging information like name and comment
     *
     * @author alex
     */
    public class TagInfo implements Serializable {

        private String name;
        private String comment;

        /**
         *
         * @param sha1 - the sha1 of the
         * @param name - the name of the tag
         * @param comment - the commit comment
         */
        protected TagInfo(String name, String comment) {
            this.setName(name);
            this.setComment(comment);
        }

        public final String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public final String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        /**
         * serializes TagInfo to JSON. Used for debugging
         */
        public final String toString() {
            return "{ name:" + getName() + ", comment:" + getComment() + "}";
        }

        /**
         * Checks whether this tag info is valid.
         *
         * @return
         */
        public final Boolean isValid() {
            return !name.isEmpty() && !comment.isEmpty();
        }

        public boolean equals(TagInfo o) {
            return getName() == o.getName();
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
