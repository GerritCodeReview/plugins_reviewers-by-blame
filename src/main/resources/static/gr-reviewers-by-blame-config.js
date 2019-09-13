// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the 'License');
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an 'AS IS' BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

(function () {
  'use strict';

  Polymer({
    is: 'gr-reviewers-by-blame-config',
    _legacyUndefinedCheck: true,

    properties: {
      repoName: String,
      _currentConfig: Object,
      _ignoreFileRegex: String,
      _ignoreRegex: String,
      _maxReviewers: Number,
      _prefsChanged: {
        type: Boolean,
        value: false,
      },
    },

    attached() {
      this._getPreferences();
    },

    _getPreferences() {
      this.plugin.restApi('/projects/')
        .get(`${this.repoName}/config`)
        .then(config => {
          if (!config) {
            return;
          }

          if (config['plugin_config']
            && config['plugin_config']['reviewers-by-blame']) {
            this._currentConfig = config['plugin_config']['reviewers-by-blame'];
            this._ignoreFileRegex = this._currentConfig.ignoreFileRegEx.value;
            this._ignoreRegex = this._currentConfig.ignoreSubjectRegEx.value;
            this._maxReviewers = this._currentConfig.maxReviewers.value;
          }
        })
    },

    _handlePrefsChanged() {
      this._prefsChanged = true;
    },

    _handlePrefsSave() {
      let body = { plugin_config_values: new Object() };
      body['plugin_config_values']['reviewers-by-blame'] = {
        ignoreFileRegEx: {
          value: this._ignoreFileRegex
        },
        ignoreSubjectRegEx: {
          value: this._ignoreRegex
        },
        maxReviewers: {
          value: this._maxReviewers
        },
      };

      this.plugin.restApi('/projects/')
        .put(`${this.repoName}/config`, body)
        .then(() => {
          this._prefsChanged = false;
        }).catch(response => {
          this.fire('show-error', { message: response });
        });
    },

    _formatInheritedValue(value) {
      if (value) {
        return `inherited: ${value}`;
      }

      return ""
    }
  });
})();
