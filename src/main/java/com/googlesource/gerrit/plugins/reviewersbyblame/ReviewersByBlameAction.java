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

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.change.WorkInProgressOp.Input;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.ProjectPermission;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.RetryingRestModifyView;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.util.RequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gwtorm.server.OrmException;
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

public class ReviewersByBlameAction extends RetryingRestModifyView<RevisionResource, Input, ChangeInfo>
    implements RestModifyView<RevisionResource, Input>, UiAction<RevisionResource> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String pluginName;
  private final PluginConfigFactory cfg;

  @Inject
  ReviewersByBlameAction(@PluginName String pluginName, final PluginConfigFactory cfg,) {
    this.pluginName = pluginName;
    this.cfg = cfg;
  }

  @Override
  protected Response<?> applyImpl(
      BatchUpdate.Factory updateFactory, RevisionResource rsrc, Input input)
      throws RestApiException, UpdateException {
    Change change = rsrc.getChange();
    Project.NameKey projectName = change.getProject();

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
      return Response.ok("");
    }
    if (maxReviewers <= 0) {
      return Response.ok("");
    }

    try (Repository git = repoManager.openRepository(projectName);
        RevWalk rw = new RevWalk(git)) {
      if (!change.getStatus().isOpen()) {
        throw new ResourceConflictException("change is " + ChangeUtil.status(change));
      }

      Change.Id changeId = new Change.Id(change.getId());
      final ChangeData cd = changeDataFactory.create(projectName, changeId);
      if (cd == null) {
        log.warn(
            "Change with id: '{}' on project key: '{}' not found.",
            changeId.get(),
            projectName.toString());
        return Response.ok("");
      }
      final Change change = cd.change();
      PatchSet.Id psId = new PatchSet.Id(changeId, rsrc.getPatchSet().getPatchSetId());
      PatchSet ps = cd.patchSet(psId);
      if (ps == null) {
        log.warn("Patch set {} not found in change {}.", psId.get(), changeId.get());
        return;
      }

      final RevCommit commit = rw.parseCommit(ObjectId.fromString(rsrc.getPatchSet().getRevision().get()));

      if (!ignoreSubjectRegEx.isEmpty() && commit.getShortMessage().matches(ignoreSubjectRegEx)) {
        Response.ok("");
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
                          });
                  try {
                    task.run();
                  } finally {
                    tl.setContext(old);
                  }
                }
              });
    } catch (OrmException | IOException x) {
      log.error(x.getMessage(), x);
    }

    return Response.ok("");
  }

  @Override
  public UiAction.Description getDescription(RevisionResource rsrc) {
    return new Description()
        .setLabel("Suggest Reviewers")
        .setTitle("Suggests Reviewers to add)
        .setVisible(
            and(
                rsrc.getChange().getStatus() == Status.NEW,
                or(
                    rsrc.isUserOwner(),
                    or(
                        permissionBackend
                            .currentUser()
                            .testCond(GlobalPermission.ADMINISTRATE_SERVER),
                        permissionBackend
                            .currentUser()
                            .project(rsrc.getProject())
                            .testCond(ProjectPermission.WRITE_CONFIG)))));
  }
}
