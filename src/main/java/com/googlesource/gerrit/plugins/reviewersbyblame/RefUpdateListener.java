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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Account.Id;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;

class RefUpdateListener implements GitReferenceUpdatedListener {

  private static final Logger log = LoggerFactory
      .getLogger(RefUpdateListener.class);

  private final ReviewersByBlame.Factory reviewersByBlameFactory;
  private final ReviewersFixed.Factory reviewersFixedFactory;
  private final GitRepositoryManager repoManager;
  private final WorkQueue workQueue;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ThreadLocalRequestContext tl;
  private final SchemaFactory<ReviewDb> schemaFactory;
  private final PluginConfigFactory cfg;
  private final String pluginName;
  private ReviewDb db;

  @Inject
  RefUpdateListener(final ReviewersByBlame.Factory reviewersByBlameFactory,
      final ReviewersFixed.Factory reviewersFixedFactory,
      final GitRepositoryManager repoManager, final WorkQueue workQueue,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final ThreadLocalRequestContext tl,
      final SchemaFactory<ReviewDb> schemaFactory,
      final PluginConfigFactory cfg,
      final @PluginName String pluginName) {
    this.reviewersByBlameFactory = reviewersByBlameFactory;
    this.reviewersFixedFactory = reviewersFixedFactory;
    this.repoManager = repoManager;
    this.workQueue = workQueue;
    this.identifiedUserFactory = identifiedUserFactory;
    this.tl = tl;
    this.schemaFactory = schemaFactory;
    this.cfg = cfg;
    this.pluginName = pluginName;
  }

  @Override
  public void onGitReferenceUpdated(final Event e) {
    Project.NameKey projectName = new Project.NameKey(e.getProjectName());

    int maxReviewers;
    Set<Account.Id> reviewers;
    Strategy strategy;
    try {
      strategy =
          cfg.getWithInheritance(projectName, pluginName)
          .getEnum("strategy", Strategy.BY_BLAME);
      maxReviewers =
          cfg.getWithInheritance(projectName, pluginName)
             .getInt("maxReviewers", 3);
      reviewers = reviewers(cfg.getWithInheritance(projectName, pluginName)
             .getStringList("reviewer"));
    } catch (NoSuchProjectException x) {
      log.error(x.getMessage(), x);
      return;
    }
    if (strategy == Strategy.BY_BLAME && maxReviewers <= 0) {
      return;
    }
    if (strategy == Strategy.FIXED && reviewers.isEmpty()) {
      return;
    }

    Repository git;
    try {
      git = repoManager.openRepository(projectName);
    } catch (RepositoryNotFoundException x) {
      log.error(x.getMessage(), x);
      return;
    } catch (IOException x) {
      log.error(x.getMessage(), x);
      return;
    }

    final ReviewDb reviewDb;
    final RevWalk rw = new RevWalk(git);

    try {
      reviewDb = schemaFactory.open();
      try {
        if (e.getRefName().startsWith("refs/changes/")) {

          PatchSet.Id psId = PatchSet.Id.fromRef(e.getRefName());
          PatchSet ps = reviewDb.patchSets().get(psId);
          if (ps == null) {
            log.warn("No patch set found for " + e.getRefName());
            return;
          }

          final Change change = reviewDb.changes().get(psId.getParentKey());
          if (change == null) {
            log.warn("No change found for " + e.getRefName());
            return;
          }

          final RevCommit commit =
              rw.parseCommit(ObjectId.fromString(e.getNewObjectId()));

          final Runnable task = (strategy ==  Strategy.BY_BLAME)
              ? reviewersByBlameFactory.create(commit, change,
                  ps, maxReviewers, git)
              : reviewersFixedFactory.create(change, reviewers);

          workQueue.getDefaultQueue().submit(new Runnable() {
            public void run() {
              RequestContext old = tl.setContext(new RequestContext() {

                @Override
                public CurrentUser getCurrentUser() {
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
        }
      } catch (OrmException x) {
        log.error(x.getMessage(), x);
      } catch (MissingObjectException x) {
        log.error(x.getMessage(), x);
      } catch (IncorrectObjectTypeException x) {
        log.error(x.getMessage(), x);
      } catch (IOException x) {
        log.error(x.getMessage(), x);
      } finally {
        reviewDb.close();
      }
    } catch (OrmException x) {
      log.error(x.getMessage(), x);
    } finally {
      rw.release();
      git.close();
    }
  }

  private Set<Id> reviewers(String[] stringList) {
    if (stringList == null || stringList.length == 0) {
      return Collections.emptySet();
    }
    return Sets.newHashSet(Iterables.transform(Arrays.asList(stringList),
        new Function<String, Account.Id>() {
          public Account.Id apply(String id) {
            return Account.Id.parse(id);
          };
        }));
  }
}
