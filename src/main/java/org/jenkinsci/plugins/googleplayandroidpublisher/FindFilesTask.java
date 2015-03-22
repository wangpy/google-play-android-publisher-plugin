package org.jenkinsci.plugins.googleplayandroidpublisher;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Task which searches for files using an Ant Fileset pattern. */
public class FindFilesTask implements FilePath.FileCallable<List<String>> {

    private final String includes;

    FindFilesTask(String includes) {
        this.includes = includes;
    }

    @Override
    public List<String> invoke(File baseDir, VirtualChannel channel) throws IOException, InterruptedException {
        String[] files = hudson.Util.createFileSet(baseDir, includes).getDirectoryScanner().getIncludedFiles();
        return Collections.unmodifiableList(Arrays.asList(files));
    }

}