package me.saket.dank.ui.submission;

import static me.saket.dank.utils.Commons.toImmutable;

import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.CheckResult;
import android.support.annotation.VisibleForTesting;

import com.f2prateek.rx.preferences2.RxSharedPreferences;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.sqlbrite2.BriteDatabase;

import net.dean.jraw.models.Contribution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import hirondelle.date4j.DateTime;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import me.saket.dank.BuildConfig;
import me.saket.dank.data.ContributionFullNameWrapper;
import me.saket.dank.data.DankRedditClient;
import me.saket.dank.ui.user.UserSession;
import timber.log.Timber;

/**
 * Manages sending replies and saving drafts.
 */
@Singleton
public class ReplyRepository implements DraftStore {

  private final DankRedditClient dankRedditClient;
  private final BriteDatabase database;
  private final UserSession userSession;
  private final Moshi moshi;
  private final int recycleDraftsOlderThanNumDays;
  private final SharedPreferences sharedPrefs;
  private final RxSharedPreferences rxSharedPrefs;

  @Inject
  public ReplyRepository(DankRedditClient dankRedditClient, BriteDatabase database, UserSession userSession,
      @Named("drafts_sharedpreferences") SharedPreferences sharedPrefs, Moshi moshi,
      @Named("drafts_max_retain_days") int recycleDraftsOlderThanNumDays)
  {
    this.dankRedditClient = dankRedditClient;
    this.database = database;
    this.userSession = userSession;
    this.sharedPrefs = sharedPrefs;
    this.rxSharedPrefs = RxSharedPreferences.create(sharedPrefs);
    this.moshi = moshi;
    this.recycleDraftsOlderThanNumDays = recycleDraftsOlderThanNumDays;
  }

// ======== REPLY ======== //

  /**
   * This exists to ensure a duplicate reply does not get stored when re-sending the same reply.
   */
  @CheckResult
  public Completable reSendReply(PendingSyncReply pendingSyncReply) {
    String parentThreadFullName = pendingSyncReply.parentThreadFullName();
    ParentThread parentThread;
    if (parentThreadFullName.startsWith("t3_")) {
      parentThread = ParentThread.createSubmission(parentThreadFullName);
    } else if (parentThreadFullName.startsWith("t4_")) {
      parentThread = ParentThread.createPrivateMessage(parentThreadFullName);
    } else {
      throw new UnsupportedOperationException("Unknown thread name: " + parentThreadFullName);
    }

    Contribution parentContribution = ContributionFullNameWrapper.create(pendingSyncReply.parentContributionFullName());
    String replyBody = pendingSyncReply.body();
    long replyCreatedTimeMillis = pendingSyncReply.createdTimeMillis();
    return sendReply(parentContribution, parentThread, replyBody, replyCreatedTimeMillis);
  }

  /**
   * @param parentContribution Parent comment/message.
   * @param parentThread       Root submission/message-thread.
   */
  @CheckResult
  public Completable sendReply(Contribution parentContribution, ParentThread parentThread, String replyBody) {
    long replyCreatedTimeMillis = System.currentTimeMillis();
    return sendReply(parentContribution, parentThread, replyBody, replyCreatedTimeMillis);
  }

  @CheckResult
  private Completable sendReply(Contribution parentContribution, ParentThread parentThread, String replyBody, long replyCreatedTimeMillis) {
    PendingSyncReply pendingSyncReply = PendingSyncReply.create(
        replyBody,
        PendingSyncReply.State.POSTING,
        parentThread.fullName(),
        parentContribution.getFullName(),
        userSession.loggedInUserName(),
        replyCreatedTimeMillis
    );

    return Completable.fromAction(() -> database.insert(PendingSyncReply.TABLE_NAME, pendingSyncReply.toValues(), SQLiteDatabase.CONFLICT_REPLACE))
        .andThen(dankRedditClient.withAuth(Single.fromCallable(() -> {
          String postedReplyId = dankRedditClient.userAccountManager().reply(parentContribution, replyBody);
          String postedFullName;
          switch (parentThread.type()) {
            case SUBMISSION:
              // Submission comment.
              postedFullName = "t4_" + postedReplyId;
              break;

            case PRIVATE_MESSAGE:
              postedFullName = "t1_" + postedReplyId;
              break;

            default:
              throw new UnsupportedOperationException("Unknown thread type: " + parentThread.type());
          }
          Timber.i("Posted full-name: %s", postedFullName);
          return postedFullName;   // full-name.
        })))
        .flatMapCompletable(postedReplyFullName -> Completable.fromAction(() -> {
          PendingSyncReply updatedPendingSyncReply = pendingSyncReply
              .toBuilder()
              .state(PendingSyncReply.State.POSTED)
              .postedFullName(postedReplyFullName)
              .build();
          database.insert(PendingSyncReply.TABLE_NAME, updatedPendingSyncReply.toValues(), SQLiteDatabase.CONFLICT_REPLACE);
        }))
        .onErrorResumeNext(error -> {
          Timber.e(error, "Couldn't send reply");

          PendingSyncReply updatedPendingSyncReply = pendingSyncReply.toBuilder()
              .state(PendingSyncReply.State.FAILED)
              .build();
          database.insert(PendingSyncReply.TABLE_NAME, updatedPendingSyncReply.toValues(), SQLiteDatabase.CONFLICT_REPLACE);
          return Completable.error(error);
        });
  }

  /**
   * Get all replies that are either awaiting to be posted or have been posted, but the comments
   * haven't been refreshed for <var>submission</var> yet.
   */
  @CheckResult
  public Observable<List<PendingSyncReply>> streamPendingSyncReplies(ParentThread parentThread) {
    return database.createQuery(PendingSyncReply.TABLE_NAME, PendingSyncReply.QUERY_GET_ALL_FOR_THREAD, parentThread.fullName())
        .mapToList(PendingSyncReply.MAPPER)
        .map(toImmutable());
  }

  @CheckResult
  public Observable<List<PendingSyncReply>> streamFailedReplies() {
    return database.createQuery(PendingSyncReply.TABLE_NAME, PendingSyncReply.QUERY_GET_ALL_FAILED)
        .mapToList(PendingSyncReply.MAPPER)
        .map(toImmutable());
  }

  /**
   * Removes POSTED "pending-sync" replies for a submission/message-thread once its comments are refreshed.
   */
  @CheckResult
  public Completable removeSyncPendingPostedReplies(ParentThread parentThread) {
    return Completable.fromAction(() -> database.delete(
        PendingSyncReply.TABLE_NAME,
        PendingSyncReply.WHERE_STATE_AND_THREAD_FULL_NAME,
        PendingSyncReply.State.POSTED.name(), parentThread.fullName()
    ));
  }

  @CheckResult
  public Completable removeAllPendingSyncReplies() {
    if (!BuildConfig.DEBUG) {
      throw new IllegalStateException();
    }
    return Completable.fromAction(() -> database.delete(PendingSyncReply.TABLE_NAME, null));
  }

// ======== DRAFTS ======== //

  @Override
  @CheckResult
  public Completable saveDraft(Contribution contribution, String draftBody) {
    if (draftBody.isEmpty()) {
      return removeDraft(contribution);
    }

    return Completable.fromAction(() -> {
      long draftCreatedTimeMillis = System.currentTimeMillis();
      ReplyDraft replyDraft = ReplyDraft.create(draftBody, draftCreatedTimeMillis);
      JsonAdapter<ReplyDraft> jsonAdapter = moshi.adapter(ReplyDraft.class);
      String replyDraftJson = jsonAdapter.toJson(replyDraft);
      rxSharedPrefs.getString(keyForDraft(contribution)).set(replyDraftJson);
      //Timber.i("Draft saved: %s", draftBody);

      // Recycle old drafts.
      Map<String, ?> allDraftJsons = new HashMap<>(sharedPrefs.getAll());
      Map<String, ReplyDraft> allDrafts = new HashMap<>(allDraftJsons.size());
      for (Map.Entry<String, ?> entry : allDraftJsons.entrySet()) {
        String draftEntryJson = (String) entry.getValue();
        //Timber.i("Existing draft: %s", draftEntryJson);
        ReplyDraft draftEntry = jsonAdapter.fromJson(draftEntryJson);
        allDrafts.put(entry.getKey(), draftEntry);
      }
      recycleOldDrafts(allDrafts);
    });
  }

  @VisibleForTesting
  void recycleOldDrafts(Map<String, ReplyDraft> allDrafts) {
    DateTime nowDateTime = DateTime.now(TimeZone.getTimeZone("UTC"));
    DateTime draftDateLimit = nowDateTime.minusDays(recycleDraftsOlderThanNumDays);
    long draftDateLimitMillis = draftDateLimit.getMilliseconds(TimeZone.getTimeZone("UTC"));

    SharedPreferences.Editor sharedPrefsEditor = sharedPrefs.edit();

    for (Map.Entry<String, ReplyDraft> entry : allDrafts.entrySet()) {
      ReplyDraft draftEntry = entry.getValue();
      if (draftEntry.createdTimeMillis() < draftDateLimitMillis) {
        // Stale draft.
        sharedPrefsEditor.remove(entry.getKey());
      }
    }

    sharedPrefsEditor.apply();
  }

  @Override
  @CheckResult
  public Observable<String> streamDrafts(Contribution contribution) {
    return rxSharedPrefs.getString(keyForDraft(contribution), "")
        .asObservable()
        .map(replyDraftJson -> {
          if ("".equals(replyDraftJson)) {
            // Following RxBinding, which always emits the default value, we'll also emit an
            // empty draft instead of Single.never() so that the UI's initial setup is done.
            return "";

          } else {
            ReplyDraft replyDraft = moshi.adapter(ReplyDraft.class).fromJson(replyDraftJson);
            //noinspection ConstantConditions
            return replyDraft.body();
          }
        })
        .distinctUntilChanged()
        //.doOnNext(draft -> Timber.i("Sending draft: %s", draft))
        ;
  }

  @Override
  public Completable removeDraft(Contribution contribution) {
    //String parent;
    //if (contribution instanceof Comment) {
    //  parent = ((Comment) contribution).getBody();
    //} else if (contribution instanceof Message) {
    //  parent = ((Message) contribution).getBody();
    //} else if (contribution instanceof Submission) {
    //  parent = ((Submission) contribution).getTitle();
    //} else if (contribution instanceof ContributionFullNameWrapper) {
    //  parent = contribution.getFullName();
    //} else {
    //  throw new UnsupportedOperationException();
    //}
    //Timber.i("Removing draft for %s", parent);
    return Completable.fromAction(() -> sharedPrefs.edit().remove(keyForDraft(contribution)).apply());
  }

  @VisibleForTesting
  static String keyForDraft(Contribution parentContribution) {
    if (parentContribution.getFullName() == null) {
      throw new NullPointerException("Wut");
    }
    return "replyDraftFor_" + parentContribution.getFullName();
  }
}
