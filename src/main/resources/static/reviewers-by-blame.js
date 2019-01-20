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

Gerrit.install(function(self) {
  if (window.Polymer) {
    // Install deprecated APIs to mimic GWT UI API.
    self.deprecated.install();
  }

  function onSuggestReviewersData(context, change, revision, onSubmit) {
    // Aliases to values in the context.
    const changeId = change._number;
    const revisionId = change.current_revision;

    function httpGet(url, callback) {
      var xhr = new XMLHttpRequest();
      xhr.open('GET', url);
      xhr.onreadystatechange = function() {
        if (xhr.readyState == XMLHttpRequest.DONE) {
          // skip special characters ")]}'\n"
          callback(!!xhr.responseText ?
                   JSON.parse(xhr.responseText.substring(5)) : {});
        }
      };
      xhr.send();
    }
    function gerritGet(url, callback) {
      (!!Gerrit.get) ? Gerrit.get(url, callback) :
          ((!!self.get) ? self.get('/../..' + url, callback) :
              httpGet(url, callback));
    }
    function callServer() {
      // Use the plugin REST API; pass only changeId;
      // let server get current patch set, project and branch info.
      gerritGet(`/changes/${changeId}/revisions/${revisionId}` +
                   '/reviewers-by-blame' );
      (!!Gerrit.refresh) ? Gerrit.refresh() : location.reload();
    }
    event.stopPropagation();
    callServer();
  }

  function onSuggestReviewers(context) {
    onSuggestReviewersData(context, context.change, context.revision, false);
  }

  var actionKey = null;
  function onShowChangePolyGerrit(change, revision) {
    var changeActions = self.changeActions();
    // Hide previous 'Find Owners' button under 'MORE'.
    changeActions.setActionHidden('revision', 'reviewers-by-blame', true);
    if (!!actionKey) {
      changeActions.removeTapListener(actionKey);
      changeActions.remove(actionKey);
    }
    actionKey = changeActions.add('revision', 'Suggest Reviewers');
    changeActions.setIcon(actionKey, 'review');
    changeActions.setTitle(actionKey, 'Suggests reviewers to add.');
    changeActions.addTapListener(actionKey,
        () => onSuggestReviewers(null, change, revision, false));
  }

  if (!!self.onAction) { // PolyGerrit does not have self.onAction
    self.onAction('revision', 'reviewers-by-blame', onSuggestReviewers);
  }

  // When using PolyGerrit, move "Find Owners" button out of the 'MORE' list.
  if (window.Polymer) {
    self.on('showchange', onShowChangePolyGerrit);
  }
});
