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

import static com.google.gerrit.server.change.RevisionResource.REVISION_KIND;

import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.api.projects.ProjectConfigEntryType;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.ProjectConfigEntry;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.Config;

public class ReviewersByBlameModule extends FactoryModule {
  private final Config gerritConfig;

  @Inject
  public ReviewersByBlameModule(@GerritServerConfig final Config config) {
    this.gerritConfig = config;
  }

  @Override
  protected void configure() {
    if (!config.getBoolean("reviewers-by-blame", null, "enableSuggestReviewers", false)) {
      DynamicSet.bind(binder(), EventListener.class).to(ChangeUpdatedListener.class);
    }
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
    bind(ProjectConfigEntry.class)
        .annotatedWith(Exports.named("ignoredUsers"))
        .toInstance(
            new ProjectConfigEntry(
                "Ignore User",
                null,
                ProjectConfigEntryType.ARRAY,
                null,
                false,
                "Ignores users that  match list specified."));
    if (config.getBoolean("reviewers-by-blame", null, "enableSuggestReviewers", false)) {
      install(
          new RestApiModule() {
            @Override
            protected void configure() {
              get(REVISION_KIND, "reviewers-by-blame").to(ReviewersByBlameAction.class);
            }
          });
      DynamicSet.bind(binder(), WebUiPlugin.class).toInstance(new JavaScriptPlugin("reviewers-by-blame.js"));
    }
  }
}
