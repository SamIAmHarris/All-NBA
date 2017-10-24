package com.gmail.jorgegilcavazos.ballislife.features.gamethread

import android.support.annotation.VisibleForTesting
import com.gmail.jorgegilcavazos.ballislife.base.BasePresenter
import com.gmail.jorgegilcavazos.ballislife.data.actions.RedditActions
import com.gmail.jorgegilcavazos.ballislife.data.actions.models.ReplyUIModel
import com.gmail.jorgegilcavazos.ballislife.data.actions.models.SaveUIModel
import com.gmail.jorgegilcavazos.ballislife.data.repository.comments.ContributionRepository
import com.gmail.jorgegilcavazos.ballislife.data.repository.gamethreads.GameThreadsRepository
import com.gmail.jorgegilcavazos.ballislife.features.common.ThreadAdapter
import com.gmail.jorgegilcavazos.ballislife.features.model.GameThreadType
import com.gmail.jorgegilcavazos.ballislife.features.model.ThreadItem
import com.gmail.jorgegilcavazos.ballislife.util.ErrorHandler
import com.gmail.jorgegilcavazos.ballislife.util.NetworkUtils
import com.gmail.jorgegilcavazos.ballislife.util.schedulers.BaseSchedulerProvider
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import net.dean.jraw.models.Comment
import net.dean.jraw.models.Submission
import net.dean.jraw.models.VoteDirection
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class GameThreadPresenterV2 @Inject constructor(
    private val gameThreadsRepository: GameThreadsRepository,
    private val redditActions: RedditActions,
    private val contributionRepository: ContributionRepository,
    private val schedulerProvider: BaseSchedulerProvider,
    private val threadsDisposable: CompositeDisposable,
    private val disposable: CompositeDisposable,
    private val networkUtils: NetworkUtils,
    private val errorHandler: ErrorHandler) : BasePresenter<GameThreadView>() {

  private lateinit var type: GameThreadType
  private lateinit var home: String
  private lateinit var visitor: String
  private var gameTimeUtc: Long = 0
  private var currentSubmission: Submission? = null
  private var shouldStream = false

  override fun attachView(view: GameThreadView) {
    super.attachView(view)
    type = view.getThreadType()
    home = view.getHome()
    visitor = view.getVisitor()
    gameTimeUtc = view.getGameTimeUtc()

    view.commentSaves()
        .subscribe { saveComment(it) }
        .addTo(disposable)

    view.commentUnsaves()
        .subscribe { unsaveComment(it) }
        .addTo(disposable)

    view.upvotes()
        .subscribe { redditActions.voteComment(it, VoteDirection.UPVOTE).subscribe() }
        .addTo(disposable)

    view.downvotes()
        .subscribe { redditActions.voteComment(it, VoteDirection.DOWNVOTE).subscribe() }
        .addTo(disposable)

    view.novotes()
        .subscribe { redditActions.voteComment(it, VoteDirection.NO_VOTE).subscribe() }
        .addTo(disposable)

    view.replies()
        .subscribe {
          contributionRepository.saveComment(it)
          view.openReplyToCommentActivity(it)
        }
        .addTo(disposable)

    view.submissionReplies()
        .subscribe {
          contributionRepository.saveSubmission(
              currentSubmission
                  ?: throw IllegalStateException("Current submission should not be null"))
          view.openReplyToSubmissionActivity(
              currentSubmission?.id
                  ?: throw IllegalStateException("Current submission should not be null"))
        }
        .addTo(disposable)

    view.streamChanges()
        .subscribe {
          if (it) {
            if (view.isPremiumPurchased()) {
              shouldStream = true
              loadGameThread()
            } else {
              view.setStreamSwitch(false)
              view.purchasePremium()
            }
          } else {
            shouldStream = false
            loadGameThread()
          }
        }
        .addTo(disposable)
  }

  override fun detachView() {
    disposable.clear()
    threadsDisposable.clear()
    super.detachView()
  }

  fun loadGameThread() {
    val gameThreadsObs = if (shouldStream) {
      gameThreadsRepository.gameThreads(home, visitor, gameTimeUtc, type)
          .repeatWhen({ o -> o.delay(10, TimeUnit.SECONDS) })
    } else {
      gameThreadsRepository.gameThreads(home, visitor, gameTimeUtc, type)
    }

    threadsDisposable.clear()
    gameThreadsObs
        .observeOn(schedulerProvider.ui(), true)
        .subscribe(
            { uiModel ->
              if (uiModel.inProgress && !shouldStream) {
                view.setLoadingIndicator(true)
                view.hideFab()
              } else {
                view.setLoadingIndicator(false)
              }

              view.hideErrorLoadingText()
              view.hideNoCommentsText()

              if (uiModel.found) {
                val submission = uiModel.submission!!
                currentSubmission = submission
                val iterator = submission.comments?.walkTree()

                val threadItems = mutableListOf<ThreadItem>()
                iterator?.forEach {
                  threadItems.add(
                      ThreadItem(
                          ThreadAdapter.TYPE_COMMENT, it,
                          it.depth))
                }

                if (threadItems.isEmpty()) {
                  view.showNoCommentsText()
                  view.hideComments()
                } else {
                  view.showComments(threadItems)
                }
                view.showFab()
              }

              if (uiModel.notFound) {
                view.hideFab()
              }

              if (uiModel.notFound) {
                view.showNoThreadText()
              } else {
                view.hideNoThreadText()
              }
            },
            { e ->
              if (!networkUtils.isNetworkAvailable()) {
                view.showNoNetAvailable()
              } else {
                view.showErrorLoadingText(errorHandler.handleError(e))
              }
              view.setLoadingIndicator(false)
            }
        )
        .addTo(threadsDisposable)
  }

  fun replyToComment(parentFullname: String, response: String) {
    redditActions.replyToComment(parentFullname, response)
        .subscribe(
            { uiModel: ReplyUIModel ->
              if (uiModel.inProgress) {
                view.showSubmittingCommentToast()
              }

              if (uiModel.notLoggedIn) {
                view.showNotLoggedInToast()
              }

              if (uiModel.parentNotFound) {
                view.showMissingParentToast()
              }

              if (uiModel.success) {
                view.showSubmittedCommentToast()
              }
            },
            { e ->
              if (!networkUtils.isNetworkAvailable()) {
                view.showNoNetAvailable()
              } else {
                view.showErrorSavingCommentToast(errorHandler.handleError(e))
              }
            }
        )
  }

  fun replyToSubmission(submissionId: String, response: String) {
    redditActions.replyToSubmission(submissionId, response)
        .subscribe(
            { uiModel ->
              if (uiModel.inProgress) {
                view.showSubmittingCommentToast()
              }

              if (uiModel.notLoggedIn) {
                view.showNotLoggedInToast()
              }

              if (uiModel.parentNotFound) {
                view.showMissingSubmissionToast()
              }

              if (uiModel.success) {
                view.showSubmittedCommentToast()
              }
            },
            { e ->
              if (!networkUtils.isNetworkAvailable()) {
                view.showNoNetAvailable()
              } else {
                view.showErrorSavingCommentToast(errorHandler.handleError(e))
              }
            }
        )
  }

  private fun saveComment(comment: Comment) {
    redditActions.saveComment(comment)
        .subscribe(
            { uiModel: SaveUIModel ->
              if (uiModel.notLoggedIn) {
                view.showNotLoggedInToast()
              }

              if (uiModel.success) {
                view.showSavedToast()
              }

              if (uiModel.inProgress) {
                view.showSavingToast()
              }
            },
            { e ->
              if (!networkUtils.isNetworkAvailable()) {
                view.showNoNetAvailable()
              } else {
                errorHandler.handleError(e)
              }
            }
        )
        .addTo(disposable)
  }

  private fun unsaveComment(comment: Comment) {
    redditActions.unsaveComment(comment)
        .subscribe(
            {
              if (it.notLoggedIn) {
                view.showNotLoggedInToast()
              }

              if (it.success) {
                view.showUnsavedToast()
              }

              if (it.inProgress) {
                view.showUnsavingToast()
              }
            },
            { e ->
              if (!networkUtils.isNetworkAvailable()) {
                view.showNoNetAvailable()
              } else {
                errorHandler.handleError(e)
              }
            }
        )
        .addTo(disposable)
  }

  @VisibleForTesting
  fun setCurrentSubmission(submission: Submission) {
    currentSubmission = submission
  }

  @VisibleForTesting
  fun setShouldStream(shouldStream: Boolean) {
    this.shouldStream = shouldStream
  }
}