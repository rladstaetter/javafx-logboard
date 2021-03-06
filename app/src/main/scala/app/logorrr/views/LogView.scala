package app.logorrr.views

import app.logorrr._
import app.logorrr.views.visual.LogVisualView
import app.util.CanLog
import javafx.beans.property.{SimpleIntegerProperty, SimpleListProperty, SimpleObjectProperty}
import javafx.beans.value.{ChangeListener, ObservableValue}
import javafx.beans.{InvalidationListener, Observable}
import javafx.collections.transformation.FilteredList
import javafx.event.{Event, EventHandler}
import javafx.scene.control._
import javafx.scene.layout._

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object LogView {

  def apply(logViewTabPane: LogViewTabPane
            , logReport: LogReport): LogView = {
    val lv = new LogView(logReport
      , logViewTabPane.sceneWidthProperty.get()
      , logViewTabPane.squareWidthProperty.get())

    lv.addFilters(DefaultFilter.seq: _*)

    /** activate invalidation listener on filtered list */
    lv.start()
    lv.sceneWidthProperty.bind(logViewTabPane.sceneWidthProperty)
    lv.squareWidthProperty.bind(logViewTabPane.squareWidthProperty)
    lv
  }

}

/**
 * Represents a single 'document' UI approach for a log file.
 *
 * One can view / interact with more than one log file at a time, using tabs here feels quite natural.
 *
 * @param logReport report instance holding information of log file to be analyzed
 * */
class LogView(val logReport: LogReport
              , val initialSceneWidth: Int
              , val initialSquareWidth: Int)
  extends Tab with CanLog {

  /** repaint if entries or filters change */
  val repaintInvalidationListener: InvalidationListener = (_: Observable) => repaint()

  def start(): Unit = {
    logReport.start()
    installInvalidationListener()
  }

  /** don't monitor file anymore if tab is closed, free invalidation listeners */
  setOnClosed(new EventHandler[Event]() {
    override def handle(t: Event): Unit = shutdown()
  })

  // logReport can change over time, thus change Tab text property accordingly
  def shutdown(): Unit = {
    logInfo(s"Closing file ${logReport.path.toAbsolutePath} ...")
    uninstallInvalidationListener()
    logReport.stop()
  }

  textProperty.bind(logReport.titleProperty)

  /** list of search filters to be applied to a Log Report */
  val filtersProperty = new SimpleListProperty[Filter](CollectionUtils.mkEmptyObservableList())

  /** bound to sceneWidthProperty of parent LogViewTabPane */
  val sceneWidthProperty = new SimpleIntegerProperty(initialSceneWidth)

  def sceneWidth = sceneWidthProperty.get()

  /** bound to squareWidthProperty of parent LogViewTabPane */
  val squareWidthProperty = new SimpleIntegerProperty(initialSquareWidth)

  def getSquareWidth = squareWidthProperty.get()

  val InitialRatio = 0.5

  /** top component for log view */
  val borderPane = new BorderPane()

  /** split visual view and text view */
  val splitPane = new SplitPane()

  /** list which holds all entries, default to display all (can be changed via buttons) */
  val filteredList = new FilteredList[LogEntry](logReport.entries)

  def installInvalidationListener(): Unit = {
    // to detect when we apply a new filter via filter buttons (see FilterButtonsToolbar)
    filteredList.predicateProperty().addListener(repaintInvalidationListener)
    logReport.entries.addListener(repaintInvalidationListener)
    // if application changes width this will trigger repaint (See Issue #9)
    // splitPane.widthProperty().addListener(repaintInvalidationListener)
  }

  def uninstallInvalidationListener(): Unit = {
    filteredList.predicateProperty().removeListener(repaintInvalidationListener)
    logReport.entries.removeListener(repaintInvalidationListener)
  }

  private val opsToolBar = new OpsToolBar(this)
  private val filterButtonsToolBar = {
    val fbtb = new FilterButtonsToolBar(this, filteredList, logReport.entries.size)
    fbtb.filtersProperty.bind(filtersProperty)
    fbtb
  }

  val opsToolBox = {
    val vb = new VBox()
    vb.getChildren.addAll(opsToolBar, filterButtonsToolBar)
    vb
  }

  val initialWidth = (sceneWidth * InitialRatio).toInt

  private lazy val logVisualView = {
    val lvv = new LogVisualView(filteredList.asScala, initialWidth, getSquareWidth)
    lvv.sisp.searchFilters.bind(filtersProperty)
    lvv
  }

  private val logTextView = new LogTextView(filteredList)

  logTextView.logColumnDefProperty.addListener(new ChangeListener[LogColumnDef] {
    override def changed(observableValue: ObservableValue[_ <: LogColumnDef], t: LogColumnDef, t1: LogColumnDef): Unit = {
      println("updating logentry")
      val copy = for (e <- filteredList.asScala) yield {
        Try(e.copy(value = "SEVERE updated!", someInstant = Option(t1.parse(e.value)))) match {
          case Success(value) => value
          case Failure(exception) => e
        }
      }
      filteredList.clear()
      filteredList.setAll(copy.asJava)
    }
  })

  val entryLabel = {
    val l = new Label("")
    l.prefWidthProperty.bind(sceneWidthProperty)
    l.setStyle(LogoRRRFonts.jetBrainsMono(20))
    l
  }

  borderPane.setTop(opsToolBox)
  borderPane.setCenter(splitPane)
  borderPane.setBottom(entryLabel)

  setContent(borderPane)

  /** to share state between visual view and text view. index can be selected by navigation in visual view */
  val selectedIndexProperty = new SimpleIntegerProperty()

  selectedIndexProperty.bind(logVisualView.selectedIndexProperty)
  selectedIndexProperty.addListener(new ChangeListener[Number] {
    override def changed(observableValue: ObservableValue[_ <: Number], t: Number, t1: Number): Unit = {
      logTextView.selectEntryByIndex(t1.intValue())
    }
  })

  val selectedEntryProperty = new SimpleObjectProperty[LogEntry]()

  selectedEntryProperty.bindBidirectional(logVisualView.selectedEntryProperty)

  selectedEntryProperty.addListener(new ChangeListener[LogEntry] {
    override def changed(observableValue: ObservableValue[_ <: LogEntry], t: LogEntry, nullableEntry: LogEntry): Unit = {
      updateEntryLabel(Option(nullableEntry))
    }
  })

  // if user changes selected item in listview, change footer as well
  logTextView.listView.getSelectionModel.selectedItemProperty.addListener(new ChangeListener[LogEntry] {
    override def changed(observableValue: ObservableValue[_ <: LogEntry], t: LogEntry, nullableEntry: LogEntry): Unit = updateEntryLabel(Option(nullableEntry))
  })


  def updateEntryLabel(someEntry: Option[LogEntry]): Unit = {
    someEntry match {
      case Some(entry) =>
        val background: Background = entry.background(filterButtonsToolBar.filterButtons.keys.toSeq)
        entryLabel.setBackground(background)
        entryLabel.setTextFill(entry.calcColor(filterButtonsToolBar.filterButtons.keys.toSeq).invert())
        entryLabel.setText(entry.value)
      case None =>
        entryLabel.setBackground(null)
        entryLabel.setText("")
    }
  }

  splitPane.getItems.addAll(logVisualView, logTextView)

  /**
   * we are interested just in the first divider. If it changes its position (which means the user interacts) then
   * update logVisualView
   * */
  splitPane.getDividers.get(0).positionProperty().addListener(new ChangeListener[Number] {
    override def changed(observableValue: ObservableValue[_ <: Number], t: Number, t1: Number): Unit = {
      val width = t1.doubleValue() * splitPane.getWidth
      repaint(width)
    }
  })


  def addFilters(filters: Filter*): Unit = filtersProperty.addAll(filters.asJava)

  def addFilter(filter: Filter): Unit = filtersProperty.add(filter)


  def removeFilter(filter: Filter): Unit = filtersProperty.remove(filter)

  def getVisualViewWidth(): Double = {
    val w = splitPane.getDividers.get(0).getPosition * splitPane.getWidth
    if (w != 0.0) {
      w
    } else {
      initialWidth
    }
  }

  def getSelectedIndex(): Int = selectedIndexProperty.get()

  /** width can be negative as well, we have to guard about that. also we repaint only if view is visible. */
  def repaint(width: Double = getVisualViewWidth()): Unit = {
    if (width > 0 && width > getSquareWidth * 4) { // at minimum we want to have 4 squares left (an arbitrary choice)
      logVisualView.repaint(getSquareWidth, width.toInt)
    } else {
      logError(s"Not painting since isSelected: $isSelected && $width > 0 && $width > ${getSquareWidth * 4}")
    }
  }


}