/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jenkinsci.plugins.multiplescms;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.plugins.git.GitAPI;
import hudson.plugins.git.GitException;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import org.eclipse.jgit.lib.ObjectId;

/**
 *
 * @author alex
 */
public final class GitTagFileCallable implements FilePath.FileCallable<String>,
        Serializable {

    protected final TaskListener listener;
    protected final FilePath workingDirectory;
    protected final EnvVars environment;
    protected final ObjectId sha1;
    protected final String tagName, tagComment;
    
    private static final long serialVersionUID = 1L;

    public GitTagFileCallable(TaskListener listener, FilePath workingDirectory, EnvVars environment, ObjectId sha1, String tagName, String tagComment) {
        this.listener = listener;
        this.workingDirectory = workingDirectory;
        this.environment = environment;
        this.sha1 = sha1;
        this.tagName = tagName;
        this.tagComment = tagComment;
    }

    public String invoke(File localWorkspace, VirtualChannel channel) throws IOException, InterruptedException, GitException {

        FilePath ws = new FilePath(localWorkspace);

        final PrintStream log = listener.getLogger();

        log.println("Tagging:" + ws.getName() + " / " + ws.getRemote() + " - " + ws.getChannel());

        // we use another git instance because invoke is done remotely. 
        GitAPI git = new GitAPI("git", workingDirectory, listener, environment, null);

        if (!git.hasGitRepo()) {
            return null;
        }

        git.tag(tagName, tagComment, sha1);

        return sha1.name();
    }
}
