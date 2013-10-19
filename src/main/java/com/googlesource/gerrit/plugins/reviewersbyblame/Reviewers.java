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

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.PostReviewers;
import com.google.gerrit.server.project.ChangeControl;
import com.google.inject.Provider;

public class Reviewers {
  private static final Logger log = LoggerFactory
      .getLogger(Reviewers.class);

  private final Provider<PostReviewers> reviewersProvider;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ChangeControl.GenericFactory changeControlFactory;

  public Reviewers(ChangeControl.GenericFactory changeControlFactory,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      Provider<PostReviewers> reviewersProvider) {
    this.changeControlFactory = changeControlFactory;
    this.reviewersProvider = reviewersProvider;
    this.identifiedUserFactory = identifiedUserFactory;
  }

  /**
   * Append the reviewers to change#{@link Change}
   *
   * @param topReviewers Set of reviewers proposed
   * @param change {@link Change} to add the reviewers to
   */
  protected void addReviewers(Set<Account.Id> reviewers, Change change) {
    try {
      ChangeControl changeControl =
          changeControlFactory.controlFor(change,
              identifiedUserFactory.create(change.getOwner()));
      ChangeResource changeResource = new ChangeResource(changeControl);
      PostReviewers post = reviewersProvider.get();
      for (Account.Id accountId : reviewers) {
        PostReviewers.Input input = new PostReviewers.Input();
        input.reviewer = accountId.toString();
        post.apply(changeResource, input);
      }
    } catch (Exception ex) {
      log.error("Couldn't add reviewers to the change", ex);
    }
  }
}
