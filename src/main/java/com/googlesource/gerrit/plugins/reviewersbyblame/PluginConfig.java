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

import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.ProjectConfig;

public class PluginConfig extends ProjectConfig {
  private int maxReviewersByBlame;

  public PluginConfig(Project.NameKey projectName) {
    super(projectName);
  }

  @Override
  protected void onLoad() throws IOException, ConfigInvalidException {
    super.onLoad();
    Config rc = readConfig("project.config");
    maxReviewersByBlame =
        rc.getInt("plugin", "reviewersbyblame", "maxReviewers", 0);
  }

  public int getMaxReviewersByBlame() {
    return maxReviewersByBlame;
  }

}
