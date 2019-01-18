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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Patch.ChangeType;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListEntry;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReviewersByBlame implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(ReviewersByBlame.class);

  private final RevCommit commit;
  private final Change change;
  private final PatchSet ps;
  private final Repository repo;
  private final int maxReviewers;
  private final String ignoreFileRegEx;
  private final List<String> ignoredUsers;

  private final Emails emails;
  private final AccountCache accountCache;
  private final PatchListCache patchListCache;
  private final GerritApi gApi;

  public interface Factory {
    ReviewersByBlame create(
        RevCommit commit,
        Change change,
        PatchSet ps,
        int maxReviewers,
        Repository repo,
        String ignoreFileRegEx,
        String[] ignoredUsers);
  }

  @Inject
  public ReviewersByBlame(
      final Emails emails,
      final AccountCache accountCache,
      final PatchListCache patchListCache,
      GerritApi gApi,
      @Assisted final RevCommit commit,
      @Assisted final Change change,
      @Assisted final PatchSet ps,
      @Assisted final int maxReviewers,
      @Assisted final Repository repo,
      @Assisted final String ignoreFileRegEx,
      @Assisted final String[] ignoredUsers) {
    this.emails = emails;
    this.accountCache = accountCache;
    this.patchListCache = patchListCache;
    this.gApi = gApi;
    this.commit = commit;
    this.change = change;
    this.ps = ps;
    this.maxReviewers = maxReviewers;
    this.repo = repo;
    this.ignoreFileRegEx = ignoreFileRegEx;
    this.ignoredUsers = ignoredUsers == null ? ImmutableList.of() : Arrays.asList(ignoredUsers);
  }

  @Override
  public void run() {
    Map<Account, Integer> reviewers = Maps.newHashMap();
    PatchList patchList;
    try {
      patchList = patchListCache.get(change, ps);
    } catch (PatchListNotAvailableException ex) {
      log.error("Couldn't load patchlist for change {}", change.getKey(), ex);
      return;
    }
    // Ignore merges and initial commit.
    if (commit.getParentCount() != 1) {
      return;
    }
    for (PatchListEntry entry : patchList.getPatches()) {
      BlameResult blameResult;
      if ((entry.getChangeType() == ChangeType.MODIFIED
              || entry.getChangeType() == ChangeType.DELETED)
          && (ignoreFileRegEx.isEmpty() || !entry.getNewName().matches(ignoreFileRegEx))
          && (blameResult = computeBlame(entry, commit.getParent(0))) != null) {
        List<Edit> edits = entry.getEdits();
        reviewers.putAll(getReviewersForPatch(edits, blameResult));
      }
    }
    Set<Account.Id> topReviewers = findTopReviewers(reviewers);
    addReviewers(topReviewers, change);
  }

  /**
   * Append the reviewers to change#{@link Change}
   *
   * @param topReviewers Set of reviewers proposed
   * @param change {@link Change} to add the reviewers to
   */
  private void addReviewers(Set<Account.Id> topReviewers, Change change) {
    try {
      ReviewInput in = new ReviewInput();
      in.reviewers = new ArrayList<>(topReviewers.size());
      for (Account.Id account : topReviewers) {
        AddReviewerInput addReviewerInput = new AddReviewerInput();
        addReviewerInput.reviewer = account.toString();
        in.reviewers.add(addReviewerInput);
      }
      gApi.changes().id(change.getChangeId()).current().review(in);
    } catch (Exception ex) {
      log.error("Couldn't add reviewers to the change", ex);
    }
  }

  /**
   * Create a set of reviewers based on data collected from line annotations, the reviewers are
   * ordered by their weight and n greatest of the entries are chosen, where n is the maximum number
   * of reviewers
   *
   * @param reviewers A set of reviewers with their weight mapped to their {@link Account}
   * @return Reviewers that are best matches for this change, empty if none, never <code>null</code>
   */
  private Set<Account.Id> findTopReviewers(final Map<Account, Integer> reviewers) {
    Set<Account.Id> topReviewers = Sets.newHashSet();
    List<Entry<Account, Integer>> entries =
        Ordering.from(
                new Comparator<Entry<Account, Integer>>() {
                  @Override
                  public int compare(
                      Entry<Account, Integer> first, Entry<Account, Integer> second) {
                    return first.getValue() - second.getValue();
                  }
                })
            .greatestOf(reviewers.entrySet(), this.maxReviewers);
    for (Entry<Account, Integer> entry : entries) {
      topReviewers.add(entry.getKey().getId());
    }
    return topReviewers;
  }

  /**
   * Get a map of all the possible reviewers based on the provided blame data
   *
   * @param edits List of edits that were made for this patch
   * @param blameResult Result of blame computation
   * @return a set of all possible reviewers, empty if none, never <code>null</code>
   */
  private Map<Account, Integer> getReviewersForPatch(
      final List<Edit> edits, final BlameResult blameResult) {
    Map<Account, Integer> reviewers = Maps.newHashMap();
    for (Edit edit : edits) {
      for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
        RevCommit commit = blameResult.getSourceCommit(i);
        try {
          Set<Account.Id> ids = emails.getAccountFor(commit.getAuthorIdent().getEmailAddress());
          for (Account.Id id : ids) {
            AccountState state = accountCache.get(id);
            Account account = state.getAccount();
            String username = state.getUserName();
            if (account.isActive()
                && !change.getOwner().equals(account.getId())
                && (username == null || !ignoredUsers.contains(username))) {
              Integer count = reviewers.get(account);
              reviewers.put(account, count == null ? 1 : count.intValue() + 1);
            }
          }
        } catch (IOException | OrmException e) {
          throw new RuntimeException(
              "Unable to get account with email: " + commit.getAuthorIdent().getEmailAddress(), e);
        }
      }
    }
    return reviewers;
  }

  /**
   * Compute the blame data for the parent, we are not interested in the specific commit but the
   * parent, since we only want to know the last person that edited this specific part of the code.
   *
   * @param entry {@link PatchListEntry}
   * @param parent Parent {@link RevCommit}
   * @return Result of blame computation, null if the computation fails
   */
  private BlameResult computeBlame(final PatchListEntry entry, final RevCommit parent) {
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
      log.error("Error while computing blame for commit {}", parent.getName(), err);
    }
    return null;
  }
}
