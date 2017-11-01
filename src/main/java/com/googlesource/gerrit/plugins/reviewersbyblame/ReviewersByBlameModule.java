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

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.config.ProjectConfigEntry;
import com.google.gerrit.server.events.EventListener;

public class ReviewersByBlameModule extends FactoryModule {
  @Override
  protected void configure() {
    DynamicSet.bind(binder(), EventListener.class).to(ChangeUpdatedListener.class);
    factory(ReviewersByBlame.Factory.class);
    bind(ProjectConfigEntry.class)
        .annotatedWith(Exports.named("maxReviewers"))
        .toInstance(
            new ProjectConfigEntry(
                "Max Reviewers",
                3,
                true,
                "The maximum number of reviewers that should be automatically added"
                    + " to a change based on the git blame computation on the changed files."));
    bind(ProjectConfigEntry.class)
        .annotatedWith(Exports.named("ignoreSubjectRegEx"))
        .toInstance(
            new ProjectConfigEntry(
                "Ignore Regex",
                "",
                true,
                "Ignore commits where the subject matches the given regular expression"));
    bind(ProjectConfigEntry.class)
        .annotatedWith(Exports.named("ignoreFileRegEx"))
        .toInstance(
            new ProjectConfigEntry(
                "Ignore file Regex",
                "",
                true,
                "Ignore files that match the given regular expression when looking for reviewers"));
  }
}
