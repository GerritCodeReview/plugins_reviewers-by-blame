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

import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.change.PostReviewers;
import com.google.gerrit.server.project.ChangeControl;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;

public class ReviewersFixed extends Reviewers implements Runnable {

  private final Change change;
  private final Set<Account.Id> reviewers;

  public interface Factory {
    ReviewersFixed create(Change change,
        Set<Account.Id> reviewers);
  }

  @Inject
  public ReviewersFixed(
      ChangeControl.GenericFactory changeControlFactory,
      Provider<PostReviewers> reviewersProvider,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      @Assisted Change change, @Assisted Set<Account.Id> reviewers) {
    super(changeControlFactory, identifiedUserFactory, reviewersProvider);
    this.change = change;
    this.reviewers = reviewers;
  }

  @Override
  public void run() {
    addReviewers(reviewers, change);
  }
}
