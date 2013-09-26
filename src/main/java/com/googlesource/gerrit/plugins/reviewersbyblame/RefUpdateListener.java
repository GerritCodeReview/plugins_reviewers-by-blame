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

import java.io.File;
import java.io.IOException;

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.Project.NameKey;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.config.SitePaths;
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
  private final static String SECTION_PROJECT = "project";
  private final static String MAX_REVIEWERS = "maxReviewers";

  private final ReviewersByBlame.Factory reviewersByBlameFactory;
  private final GitRepositoryManager repoManager;
  private final WorkQueue workQueue;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ThreadLocalRequestContext tl;
  private final SchemaFactory<ReviewDb> schemaFactory;
  private final PluginConfigFactory pluginCfg;
  private final ProjectConfiguration config;
  private ReviewDb db;

  @Inject
  RefUpdateListener(final ReviewersByBlame.Factory reviewersByBlameFactory,
      final GitRepositoryManager repoManager, final WorkQueue workQueue,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final ThreadLocalRequestContext tl,
      final SchemaFactory<ReviewDb> schemaFactory,
      final PluginConfigFactory pluginCfg,
      final SitePaths site) {
    this.reviewersByBlameFactory = reviewersByBlameFactory;
    this.repoManager = repoManager;
    this.workQueue = workQueue;
    this.identifiedUserFactory = identifiedUserFactory;
    this.tl = tl;
    this.schemaFactory = schemaFactory;
    this.pluginCfg = pluginCfg;
    this.config = get(readConfigFileFor(site));
  }

  private interface ProjectConfiguration {
    boolean isEnabledFor(Project.NameKey p);
    int getMaxReviewersFor(Project.NameKey p);
  }

  private ProjectConfiguration get(final Config siteConfig) {
    if (siteConfig != null) {
      final boolean explicitlyEnableProjects = siteConfig
          .getBoolean("gerrit", null, "explicitlyEnableProjects", true);
      return new ProjectConfiguration() {
        @Override
        public boolean isEnabledFor(NameKey p) {
          if (!explicitlyEnableProjects) {
            return true;
          }
          return siteConfig.getBoolean(SECTION_PROJECT, p.get(),
              "enabled", false);
        }

        @Override
        public int getMaxReviewersFor(NameKey p) {
          return siteConfig.getInt(SECTION_PROJECT, p.get(),
              MAX_REVIEWERS, 3);
        }
      };
    } else {
      return new ProjectConfiguration() {
        @Override
        public boolean isEnabledFor(NameKey p) {
          int maxReviewers;
          try {
            maxReviewers = readEntry(p);
          } catch (NoSuchProjectException x) {
            log.error(x.getMessage(), x);
            return false;
          }
          return maxReviewers > 0;
        }

        @Override
        public int getMaxReviewersFor(NameKey p) {
          try {
            return readEntry(p);
          } catch (NoSuchProjectException x) {
            // already checked
            return 0;
          }
        }

        private int readEntry(NameKey p) throws NoSuchProjectException {
          return pluginCfg.getWithInheritance(p, "reviewers-by-blame")
              .getInt(MAX_REVIEWERS, 3);
        }
      };
    }
  }

  private Config readConfigFileFor(SitePaths site) {
    File file = new File(site.etc_dir, "reviewers-by-blame.config");
    FileBasedConfig cfg = new FileBasedConfig(file, FS.DETECTED);
    if (!cfg.getFile().exists()) {
      log.info(String.format("can not find config file: %s",
          file.getAbsolutePath()));
      return null;
    }

    if (cfg.getFile().length() == 0) {
      log.info(String.format("empty config file: %s",
          file.getAbsolutePath()));
      return null;
    }

    try {
      cfg.load();
      return cfg;
    } catch (ConfigInvalidException e) {
      log.info(String.format(
          "config file %s is invalid: %s",
          cfg.getFile(), e.getMessage()), e);
    } catch (IOException e) {
      log.info(String.format("cannot read %s: %s",
          cfg.getFile(), e.getMessage()), e);
    }

    return null;
  }

  @Override
  public void onGitReferenceUpdated(final Event e) {
    Project.NameKey project = new Project.NameKey(e.getProjectName());
    if (!config.isEnabledFor(project)) {
      return;
    }

    Repository git;
    try {
      git = repoManager.openRepository(project);
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
        for (Update u : e.getUpdates()) {
          if (!u.getRefName().startsWith("refs/changes/")) {
            continue;
          }

          PatchSet.Id psId = PatchSet.Id.fromRef(u.getRefName());
          PatchSet ps = reviewDb.patchSets().get(psId);
          final Change change = reviewDb.changes().get(psId.getParentKey());
          if (change == null) {
            log.warn("No change found for " + u.getRefName());
            continue;
          }

          final RevCommit commit =
              rw.parseCommit(ObjectId.fromString(u.getNewObjectId()));

          final Runnable task =
              reviewersByBlameFactory.create(commit, change, ps,
                  config.getMaxReviewersFor(project), git);

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

}
