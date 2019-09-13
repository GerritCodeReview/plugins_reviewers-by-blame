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
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.webui.JavaScriptPlugin;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.httpd.plugins.HttpPluginModule;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;

public class HttpModule extends HttpPluginModule {

  private final PluginConfigFactory cfgFactory;
  private final String pluginName;

  @Inject
  HttpModule(PluginConfigFactory cfgFactory, @PluginName String pluginName) {
    this.cfgFactory = cfgFactory;
    this.pluginName = pluginName;
  }

  @Override
  protected void configureServlets() {
    DynamicSet.bind(binder(), WebUiPlugin.class)
      .toInstance(new JavaScriptPlugin("gr-reviewers-by-blame.html"));
  }
}
