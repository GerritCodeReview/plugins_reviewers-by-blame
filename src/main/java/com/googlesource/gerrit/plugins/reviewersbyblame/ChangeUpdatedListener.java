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

import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.reviewdb.client.Branch;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeIdPredicate;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.query.change.ProjectPredicate;
import com.google.gerrit.server.query.change.RefPredicate;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
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
  private final SchemaFactory<ReviewDb> schemaFactory;
  private final PluginConfigFactory cfg;
  private final String pluginName;
  private final Provider<InternalChangeQuery> queryProvider;
  private ReviewDb db;

  @Inject
  ChangeUpdatedListener(
      final ReviewersByBlame.Factory reviewersByBlameFactory,
      final GitRepositoryManager repoManager,
      final WorkQueue workQueue,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final ThreadLocalRequestContext tl,
      final SchemaFactory<ReviewDb> schemaFactory,
      final PluginConfigFactory cfg,
      final Provider<InternalChangeQuery> queryProvider,
      final @PluginName String pluginName) {
    this.reviewersByBlameFactory = reviewersByBlameFactory;
    this.repoManager = repoManager;
    this.workQueue = workQueue;
    this.identifiedUserFactory = identifiedUserFactory;
    this.tl = tl;
    this.schemaFactory = schemaFactory;
    this.cfg = cfg;
    this.queryProvider = queryProvider;
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
      maxReviewers =
          cfg.getFromProjectConfigWithInheritance(projectName, pluginName)
              .getInt("maxReviewers", 3);
      ignoreSubjectRegEx =
          cfg.getFromProjectConfigWithInheritance(projectName, pluginName)
              .getString("ignoreSubjectRegEx", "");
      ignoreFileRegEx =
          cfg.getFromProjectConfigWithInheritance(projectName, pluginName)
              .getString("ignoreFileRegEx", "");
    } catch (NoSuchProjectException x) {
      log.error(x.getMessage(), x);
      return;
    }
    if (maxReviewers <= 0) {
      return;
    }

    try (Repository git = repoManager.openRepository(projectName);
        RevWalk rw = new RevWalk(git)) {
      final ChangeData cd = getChangeData(e.getChangeKey(), projectName, e.getRefName());
      if (cd == null) {
        log.warn(
            "Unique change with key: '"
                + e.getChangeKey().toString()
                + "' on project key: '"
                + projectName.toString()
                + "' for ref: '"
                + e.getRefName()
                + "' not found.");
        return;
      }
      final Change change = cd.change();
      Change.Id changeId = new Change.Id(e.change.get().number);
      PatchSet.Id psId = new PatchSet.Id(changeId, e.patchSet.get().number);
      PatchSet ps = cd.patchSet(psId);
      if (ps == null) {
        log.warn("Patch set " + psId.get() + " not found in change " + changeId.get() + ".");
        return;
      }

      final RevCommit commit = rw.parseCommit(ObjectId.fromString(e.patchSet.get().revision));

      if (!ignoreSubjectRegEx.isEmpty() && commit.getShortMessage().matches(ignoreSubjectRegEx)) {
        return;
      }

      final Runnable task =
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

                            @Override
                            public Provider<ReviewDb> getReviewDbProvider() {
                              return new Provider<ReviewDb>() {
                                @Override
                                public ReviewDb get() {
                                  if (db == null) {
                                    try {
                                      db = schemaFactory.open();
                                    } catch (OrmException e) {
                                      throw new ProvisionException("Cannot open ReviewDb", e);
                                    }
                                  }
                                  return db;
                                }
                              };
                            }
                          });
                  try {
                    task.run();
                  } finally {
                    tl.setContext(old);
                    if (db != null) {
                      db.close();
                      db = null;
                    }
                  }
                }
              });
    } catch (OrmException | IOException x) {
      log.error(x.getMessage(), x);
    }
  }

  private ChangeData getChangeData(Change.Key ck, Project.NameKey pnk, String ref)
      throws OrmException {
    InternalChangeQuery icq = queryProvider.get();
    Predicate<ChangeData> terms =
        Predicate.and(
            new ChangeIdPredicate(ck.get()),
            new ProjectPredicate(pnk.get()),
            new RefPredicate(new Branch.NameKey(pnk, ref).get()));
    java.util.List<ChangeData> changes = icq.query(terms);
    if (changes.size() != 1) {
      // this should never happen
      log.warn(
          "Querying for the change returned "
              + Integer.toString(changes.size())
              + " results, (expected exactly 1).");
      log.warn("Query terms were: " + terms.toString());
      log.warn("Output was: " + changes.toString());
      return null;
    }
    return changes.get(0);
  }
}
