// Copyright (C) 2019 The Android Open Source Project
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

import static com.google.gerrit.extensions.conditions.BooleanCondition.and;
import static com.google.gerrit.extensions.conditions.BooleanCondition.or;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Change.Status;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
class ReviewersByBlameAction implements RestReadView<RevisionResource>, UiAction<RevisionResource> {
  private static final Logger log = LoggerFactory.getLogger(ReviewersByBlameAction.class);

  private final String pluginName;
  private final PluginConfigFactory cfg;
  private final ThreadLocalRequestContext tl;
  private final ChangeData.Factory changeDataFactory;
  private final PermissionBackend permissionBackend;
  private final WorkQueue workQueue;
  private final GitRepositoryManager repoManager;
  private final ReviewersByBlame.Factory reviewersByBlameFactory;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;

  @Inject
  ReviewersByBlameAction(
      @PluginName String pluginName,
      final PluginConfigFactory cfg,
      final ThreadLocalRequestContext tl,
      final ChangeData.Factory changeDataFactory,
      final PermissionBackend permissionBackend,
      final WorkQueue workQueue,
      final GitRepositoryManager repoManager,
      final ReviewersByBlame.Factory reviewersByBlameFactory,
      final IdentifiedUser.GenericFactory identifiedUserFactory) {
    this.pluginName = pluginName;
    this.cfg = cfg;
    this.tl = tl;
    this.changeDataFactory = changeDataFactory;
    this.permissionBackend = permissionBackend;
    this.workQueue = workQueue;
    this.repoManager = repoManager;
    this.reviewersByBlameFactory = reviewersByBlameFactory;
    this.identifiedUserFactory = identifiedUserFactory;
  }

  @Override
  public Response<?> apply(RevisionResource rsrc)
      throws RestApiException, UpdateException {
    Change change = rsrc.getChange();
    Project.NameKey projectName = change.getProject();

    int maxReviewers;
    String ignoreSubjectRegEx;
    String ignoreFileRegEx;
    String[] ignoredUsers;
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
      ignoredUsers =
          cfg.getFromProjectConfigWithInheritance(projectName, pluginName)
              .getStringList("ignoredUsers");
    } catch (NoSuchProjectException x) {
      log.error(x.getMessage(), x);
      return Response.none();
    }
    if (maxReviewers <= 0) {
      return Response.none();
    }

    try (Repository git = repoManager.openRepository(projectName);
        RevWalk rw = new RevWalk(git);
        ReviewDb reviewDb = schemaFactory.open()) {
      Change.Id changeId = new Change.Id(change.getChangeId());
      final ChangeData cd = changeDataFactory.create(reviewDb, projectName, changeId);
      if (cd == null) {
        log.warn(
            "Change with id: '{}' on project key: '{}' not found.",
            changeId.get(),
            projectName.toString());
        return Response.none();
      }
      final Change changeCd = cd.change();
      PatchSet.Id psId = new PatchSet.Id(changeId, rsrc.getPatchSet().getPatchSetId());
      PatchSet ps = cd.patchSet(psId);
      if (ps == null) {
        log.warn("Patch set {} not found in change {}.", psId.get(), changeId.get());
        return Response.none();
      }

      final RevCommit commit = rw.parseCommit(ObjectId.fromString(rsrc.getPatchSet().getRevision().get()));

      if (!ignoreSubjectRegEx.isEmpty() && commit.getShortMessage().matches(ignoreSubjectRegEx)) {
        return Response.none();
      }

      final Runnable task =
          reviewersByBlameFactory.create(commit, changeCd, ps, maxReviewers, git, ignoreFileRegEx, ignoredUsers);

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
      return Response.none();
    }

    return Response.ok("");
  }

  @Override
  public UiAction.Description getDescription(RevisionResource rsrc) {
    return new UiAction.Description()
        .setLabel("Suggest Reviewers")
        .setTitle("Suggests Reviewers to add")
        .setVisible(
            and(
                rsrc.getChange().getStatus() == Status.NEW,
                or(
                    rsrc.getChangeResource().isUserOwner(),
                    or(
                        permissionBackend
                            .currentUser()
                            .testCond(GlobalPermission.ADMINISTRATE_SERVER),
                        permissionBackend
                            .currentUser()
                            .project(rsrc.getProject())
                            .testCond(ProjectPermission.WRITE_CONFIG)))));
  }

  static class Input {}
}
