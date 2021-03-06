// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.pullrequest.ui

import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.progress.util.ProgressWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.*
import com.intellij.ui.components.panels.Wrapper
import com.intellij.util.EventDispatcher
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import com.intellij.vcs.log.ui.frame.ProgressStripe
import org.jetbrains.plugins.github.api.data.GithubSearchedIssue
import org.jetbrains.plugins.github.pullrequest.action.GithubPullRequestKeys
import org.jetbrains.plugins.github.pullrequest.data.GithubPullRequestsDetailsLoader
import org.jetbrains.plugins.github.pullrequest.data.GithubPullRequestsLoader
import org.jetbrains.plugins.github.pullrequest.data.SingleWorkerProcessExecutor
import org.jetbrains.plugins.github.pullrequest.search.GithubPullRequestSearchComponent
import org.jetbrains.plugins.github.pullrequest.search.GithubPullRequestSearchModel
import java.awt.Component
import java.util.*
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.ScrollPaneConstants
import javax.swing.event.ListSelectionEvent

class GithubPullRequestsListComponent(project: Project,
                                      actionManager: ActionManager,
                                      autoPopupController: AutoPopupController,
                                      popupFactory: JBPopupFactory,
                                      private val externalDataProvider: DataProvider,
                                      private val detailsLoader: GithubPullRequestsDetailsLoader,
                                      private val loader: GithubPullRequestsLoader)
  : BorderLayoutPanel(), Disposable,
    GithubPullRequestsLoader.PullRequestsLoadingListener, SingleWorkerProcessExecutor.ProcessStateListener,
    DataProvider {

  private val tableModel = GithubPullRequestsTableModel()
  private val table = GithubPullRequestsTable(tableModel)
  private val scrollPane = ScrollPaneFactory.createScrollPane(table,
                                                              ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                                                              ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER).apply {
    border = JBUI.Borders.empty()
    verticalScrollBar.model.addChangeListener { potentiallyLoadMore() }
  }
  private var loadOnScrollThreshold = true
  private var isDisposed = false
  private val errorPanel = HtmlErrorPanel()
  private val progressStripe = ProgressStripe(scrollPane, this, ProgressWindow.DEFAULT_PROGRESS_DIALOG_POSTPONE_TIME_MILLIS)
  private val searchModel = GithubPullRequestSearchModel()
  private val search = GithubPullRequestSearchComponent(project, autoPopupController, popupFactory, searchModel)
  private val tableToolbarWrapper: Wrapper

  private val selectionEventDispatcher = EventDispatcher.create(PullRequestSelectionListener::class.java)

  init {
    loader.addProcessListener(this, this)
    loader.addLoadingListener(this, this)

    searchModel.addListener(object : GithubPullRequestSearchModel.StateListener {
      override fun queryChanged() {
        loader.setSearchQuery(searchModel.query)
        loader.reset()
      }
    }, this)

    table.selectionModel.addListSelectionListener { e: ListSelectionEvent ->
      if (!e.valueIsAdjusting) {
        if (table.selectedRow < 0) selectionEventDispatcher.multicaster.selectionChanged(null)
        else selectionEventDispatcher.multicaster.selectionChanged(tableModel.getValueAt(table.selectedRow, 0))
      }
    }
    selectionEventDispatcher.addListener(detailsLoader, this)

    val toolbar = actionManager.createActionToolbar("GithubPullRequestListToolbar",
                                                    actionManager.getAction("Github.PullRequest.ToolWindow.List.Toolbar") as ActionGroup,
                                                    true)
      .apply {
        setReservePlaceAutoPopupIcon(false)
        setTargetComponent(this@GithubPullRequestsListComponent)
      }

    val popupHandler = object : PopupHandler() {
      override fun invokePopup(comp: Component, x: Int, y: Int) {
        if (TableUtil.isPointOnSelection(table, x, y)) {
          val popupMenu = actionManager
            .createActionPopupMenu("GithubPullRequestListPopup",
                                   actionManager.getAction("Github.PullRequest.ToolWindow.List.Popup") as ActionGroup)
          popupMenu.setTargetComponent(this@GithubPullRequestsListComponent)
          popupMenu.component.show(comp, x, y)
        }
      }
    }
    table.addMouseListener(popupHandler)

    tableToolbarWrapper = Wrapper(toolbar.component)

    val headerPanel = JBUI.Panels.simplePanel(0, 0).addToCenter(search).addToRight(tableToolbarWrapper)
    val tableWithError = JBUI.Panels
      .simplePanel(progressStripe)
      .addToTop(errorPanel)
      .withBorder(IdeBorderFactory.createBorder(SideBorder.TOP))

    addToCenter(tableWithError)
    addToTop(headerPanel)

    resetSearch()
  }

  override fun getData(dataId: String): Any? {
    return when {
      GithubPullRequestKeys.PULL_REQUESTS_LOADER.`is`(dataId) -> loader
      GithubPullRequestKeys.PULL_REQUESTS_DETAILS_LOADER.`is`(dataId) -> detailsLoader
      else -> externalDataProvider.getData(dataId) ?: table.getData(dataId)
    }
  }

  fun setToolbarHeightReferent(referent: JComponent) {
    tableToolbarWrapper.setVerticalSizeReferent(referent)
  }

  fun addSelectionListener(listener: PullRequestSelectionListener, disposable: Disposable) =
    selectionEventDispatcher.addListener(listener, disposable)

  private fun potentiallyLoadMore() {
    if (loadOnScrollThreshold && isScrollAtThreshold(scrollPane.verticalScrollBar)) {
      loadMore()
    }
  }

  private fun loadMore() {
    if (isDisposed) return
    loadOnScrollThreshold = false
    loader.requestLoadMore()
  }

  private fun isScrollAtThreshold(verticalScrollBar: JScrollBar): Boolean {
    val visibleAmount = verticalScrollBar.visibleAmount
    val value = verticalScrollBar.value
    val maximum = verticalScrollBar.maximum
    if (maximum == 0) return false
    val scrollFraction = (visibleAmount + value) / maximum.toFloat()
    if (scrollFraction < 0.5) return false
    return true
  }

  override fun processStarted() {
    table.emptyText.text = "Loading pull requests..."
    errorPanel.setError(null)
    progressStripe.startLoading()
  }

  override fun processFinished() {
    progressStripe.stopLoading()
  }

  override fun moreDataLoaded(data: List<GithubSearchedIssue>, hasNext: Boolean) {
    if (searchModel.query.isEmpty()) {
      table.emptyText.text = "No pull requests loaded."
    }
    else {
      table.emptyText.text = "No pull requests matching filters."
      table.emptyText.appendSecondaryText("Reset Filters", SimpleTextAttributes.LINK_ATTRIBUTES) {
        resetSearch()
      }
    }
    loadOnScrollThreshold = hasNext
    tableModel.addItems(data)

    //otherwise scrollbar will have old values (before data insert)
    scrollPane.viewport.validate()
    potentiallyLoadMore()
  }

  private fun resetSearch() {
    search.searchText = "state:open"
  }

  override fun loaderReset() {
    loadOnScrollThreshold = false
    tableModel.clear()
    loader.requestLoadMore()
  }

  override fun loadingErrorOccurred(error: Throwable) {
    loadOnScrollThreshold = false
    val prefix = if (table.isEmpty) "Cannot load pull requests." else "Cannot load full pull requests list."
    table.emptyText.clear().appendText(prefix, SimpleTextAttributes.ERROR_ATTRIBUTES)
      .appendSecondaryText(getLoadingErrorText(error), SimpleTextAttributes.ERROR_ATTRIBUTES, null)
      .appendSecondaryText("Retry", SimpleTextAttributes.LINK_ATTRIBUTES) { loadMore() }
    if (!table.isEmpty) {
      //language=HTML
      val errorText = "<html><body>$prefix ${getLoadingErrorText(error)}<a href=''>Retry</a></body></html>"
      errorPanel.setError(errorText, linkActivationListener = { loadMore() })
    }
  }

  private fun getLoadingErrorText(error: Throwable): String {
    return error.message?.let { addDotIfNeeded(it) }?.let { addSpaceIfNeeded(it) }
           ?: "Unknown loading error. "
  }

  private fun addDotIfNeeded(line: String) = if (line.endsWith('.')) line else "$line."
  private fun addSpaceIfNeeded(line: String) = if (line.endsWith(' ')) line else "$line "

  override fun dispose() {
    isDisposed = true
  }

  interface PullRequestSelectionListener : EventListener {
    fun selectionChanged(selection: GithubSearchedIssue?)
  }
}