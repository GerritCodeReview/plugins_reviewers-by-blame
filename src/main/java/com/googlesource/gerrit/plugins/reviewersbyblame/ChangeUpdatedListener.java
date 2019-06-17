// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewersbyblame;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ChangeUpdatedListener implements EventListener {

  private static final Logger log = LoggerFactory.getLogger(ChangeUpdatedListener.class);

  private final ReviewersByBlame.Factory reviewersByBlameFactory;
  private final GitRepositoryManager repoManager;
  private final WorkQueue workQueue;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ThreadLocalRequestContext tl;
  private final PluginConfigFactory cfg;
  private final String pluginName;
  private final ChangeData.Factory changeDataFactory;

  @Inject
  ChangeUpdatedListener(
      ReviewersByBlame.Factory reviewersByBlameFactory,
      GitRepositoryManager repoManager,
      WorkQueue workQueue,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      ThreadLocalRequestContext tl,
      PluginConfigFactory cfg,
      ChangeData.Factory changeDataFactory,
      @PluginName String pluginName) {
    this.reviewersByBlameFactory = reviewersByBlameFactory;
    this.repoManager = repoManager;
    this.workQueue = workQueue;
    this.identifiedUserFactory = identifiedUserFactory;
    this.tl = tl;
    this.cfg = cfg;
    this.changeDataFactory = changeDataFactory;
    this.pluginName = pluginName;
  }

  @Override
  public void onEvent(Event event) {
    if (!(event instanceof PatchSetCreatedEvent)) {
      return;
    }
    PatchSetCreatedEvent e = (PatchSetCreatedEvent) event;
    Project.NameKey projectName = e.getProjectNameKey();

    int maxReviewers;
    String ignoreSubjectRegEx;
    String ignoreFileRegEx;
    try {
      PluginConfig projectConfig = cfg.getFromProjectConfigWithInheritance(projectName, pluginName);
      maxReviewers = projectConfig.getInt("maxReviewers", 3);
      ignoreSubjectRegEx = projectConfig.getString("ignoreSubjectRegEx", "");
      ignoreFileRegEx = projectConfig.getString("ignoreFileRegEx", "");
    } catch (NoSuchProjectException x) {
      log.error(x.getMessage(), x);
      return;
    }
    if (maxReviewers <= 0) {
      return;
    }

    try (Repository git = repoManager.openRepository(projectName);
        RevWalk rw = new RevWalk(git)) {
      Change.Id changeId = new Change.Id(e.change.get().number);
      ChangeData cd = changeDataFactory.create(projectName, changeId);
      if (cd == null) {
        log.warn(
            "Change with id: '{}' on project key: '{}' not found.",
            changeId.get(),
            projectName.toString());
        return;
      }
      Change change = cd.change();
      PatchSet.Id psId = new PatchSet.Id(changeId, e.patchSet.get().number);
      PatchSet ps = cd.patchSet(psId);
      if (ps == null) {
        log.warn("Patch set {} not found in change {}.", psId.get(), changeId.get());
        return;
      }

      RevCommit commit = rw.parseCommit(ObjectId.fromString(e.patchSet.get().revision));

      if (!ignoreSubjectRegEx.isEmpty() && commit.getShortMessage().matches(ignoreSubjectRegEx)) {
        return;
      }

      Runnable task =
          reviewersByBlameFactory.create(commit, change, ps, maxReviewers, git, ignoreFileRegEx);

      workQueue
          .getDefaultQueue()
          .submit(
              new Runnable() {
                @Override
                public void run() {
                  RequestContext old =
                      tl.setContext(
                          new RequestContext() {

                            @Override
                            public CurrentUser getUser() {
                              return identifiedUserFactory.create(change.getOwner());
                            }
                          });
                  try {
                    task.run();
                  } finally {
                    tl.setContext(old);
                  }
                }
              });
    } catch (IOException x) {
      log.error(x.getMessage(), x);
    }
  }
}
