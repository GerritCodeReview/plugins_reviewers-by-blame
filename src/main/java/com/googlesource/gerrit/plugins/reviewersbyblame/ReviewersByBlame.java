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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Patch.ChangeType;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountByEmailCache;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.PostReviewers;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListEntry;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.ProjectCache;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;


public class ReviewersByBlame implements Runnable {

  private final RevCommit commit;
  private final Change change;
  private final PatchSet ps;
  private final Repository repo;
  private final int maxReviewers;

  private final AccountByEmailCache byEmailCache;
  private final AccountCache accountCache;
  private final PatchListCache patchListCache;
  private final Provider<PostReviewers> reviewersProvider;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ChangeControl.GenericFactory changeControlFactory;

  private static final Logger log = LoggerFactory
      .getLogger(ReviewersByBlame.class);

  public interface Factory {
    ReviewersByBlame create(RevCommit commit, Change change, PatchSet ps,
        int maxReviewers, Repository repo);
  }

  @Inject
  public ReviewersByBlame(final AccountByEmailCache byEmailCache,
      final AccountCache accountCache,
      final ChangeControl.GenericFactory changeControlFactory,
      final Provider<PostReviewers> reviewersProvider,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final PatchListCache patchListCache, final ProjectCache projectCache,
      @Assisted final RevCommit commit, @Assisted final Change change,
      @Assisted final PatchSet ps, @Assisted final int maxReviewers,
      @Assisted final Repository repo) {
    this.byEmailCache = byEmailCache;
    this.accountCache = accountCache;
    this.changeControlFactory = changeControlFactory;
    this.reviewersProvider = reviewersProvider;
    this.identifiedUserFactory = identifiedUserFactory;
    this.patchListCache = patchListCache;
    this.commit = commit;
    this.change = change;
    this.ps = ps;
    this.maxReviewers = maxReviewers;
    this.repo = repo;
  }

  @Override
  public void run() {
    Map<Account, Integer> reviewers = Maps.newHashMap();
    PatchList patchList = null;
    try {
      patchList = patchListCache.get(change, ps);
    } catch (PatchListNotAvailableException ex) {
      log.error("Couldn't load patchlist for change {}", change.getKey(), ex);
    }
    // Ignore merges and initial commit.
    if (commit.getParentCount() == 1 && null != patchList) {
      for (PatchListEntry entry : patchList.getPatches()) {
        BlameResult blameResult;
        if ((entry.getChangeType() == ChangeType.MODIFIED ||
            entry.getChangeType() == ChangeType.DELETED)
            && (blameResult = computeBlame(entry, commit.getParent(0))) != null) {
          List<Edit> edits = entry.getEdits();
          reviewers.putAll(getReviewersForPatch(edits, blameResult));
        }
      }
      Set<Account.Id> topReviewers =
          findTopReviewers(Collections.unmodifiableSet(reviewers.entrySet()), maxReviewers);
      addReviewers(topReviewers, change);
    }
  }

  /**
   * Append the reviewers to change#{@link Change}
   *
   * @param topReviewers Set of reviewers proposed
   * @param change {@link Change} to add the reviewers to
   */
  private void addReviewers(Set<Account.Id> topReviewers, Change change) {
    try {
      ChangeControl changeControl =
          changeControlFactory.controlFor(change,
              identifiedUserFactory.create(change.getOwner()));
      ChangeResource changeResource = new ChangeResource(changeControl);
      PostReviewers post = reviewersProvider.get();
      for (Account.Id accountId : topReviewers) {
        PostReviewers.Input input = new PostReviewers.Input();
        input.reviewer = accountId.toString();
        post.apply(changeResource, input);
      }
    } catch (Exception ex) {
      log.error("Couldn't add reviewers to the change", ex);
    }
  }


  /**
   * Create a set of reviewers based on data collected from line annotations,
   * the implementation simply puts all the data in a {@link PriorityQueue} and
   * polls until the queue is exhausted or the limit for max number of reviewers
   * is reached.
   *
   * @param reviewers A set of reviewers with their weight mapped to their
   *        {@link Account}
   * @return Reviewers that are best matches for this change, empty if none,
   *         never <code>null</code>
   */
  private Set<Account.Id> findTopReviewers(
      final Set<Entry<Account, Integer>> reviewers, final int max) {
    Set<Account.Id> topReviewers = Sets.newHashSet();
    Queue<Entry<Account, Integer>> pq =
        new PriorityQueue<Map.Entry<Account, Integer>>(reviewers.size(),
            new Comparator<Entry<Account, Integer>>() {
              public int compare(Entry<Account, Integer> first,
                  Entry<Account, Integer> second) {
                return second.getValue() - first.getValue();
              }
            });
    pq.addAll(reviewers);
    int curr = 0;
    while (!pq.isEmpty() && curr < max) {
      topReviewers.add(pq.poll().getKey().getId());
      ++curr;
    }
    return topReviewers;
  }

  /**
   * Get a map of all the possible reviewers based on the provided blame data
   *
   * @param edits List of edits that were made for this patch
   * @param blameResult Result of blame computation
   * @return a set of all possible reviewers, empty if none, never
   *         <code>null</code>
   */
  private Map<Account, Integer> getReviewersForPatch(final List<Edit> edits,
      final BlameResult blameResult) {
    Map<Account, Integer> reviewers = Maps.newHashMap();
    for (Edit edit : edits) {
      for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
        RevCommit commit = blameResult.getSourceCommit(i);
        Set<Account.Id> ids =
            byEmailCache.get(commit.getAuthorIdent().getEmailAddress());
        for (Account.Id id : ids) {
          Account account = accountCache.get(id).getAccount();
          if (account.isActive() && !change.getOwner().equals(account.getId())) {
            Integer count = reviewers.get(account);
            reviewers.put(account, count == null ? 1 : count.intValue() + 1);
          }
        }
      }
    }
    return reviewers;
  }

  /**
   * Compute the blame data for the parent, we are not interested in the
   * specific commit but the parent, since we only want to know the last person
   * that edited this specific part of the code.
   *
   * @param entry {@link PatchListEntry}
   * @param commit Parent {@link RevCommit}
   * @return Result of blame computation, null if the computation fails
   */
  private BlameResult computeBlame(final PatchListEntry entry,
      final RevCommit parent) {
    BlameCommand blameCommand = new BlameCommand(repo);
    blameCommand.setStartCommit(parent);
    blameCommand.setFilePath(entry.getNewName());
    try {
      BlameResult blameResult = blameCommand.call();
      blameResult.computeAll();
      return blameResult;
    } catch (GitAPIException ex) {
      log.error("Couldn't execute blame for commit {}", parent.getName(), ex);
    } catch (IOException err) {
      log.error("Error while computing blame for commit {}", parent.getName(),
          err);
    }
    return null;
  }

}
