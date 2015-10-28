package jsky.app.ot.gemini.obscat

import java.io.File
import javax.swing.ImageIcon

import edu.gemini.catalog.ui.PreferredSizeFrame
import edu.gemini.shared.gui.textComponent.TextRenderer
import edu.gemini.ui.miglayout.MigPanel
import edu.gemini.ui.miglayout.constraints._
import edu.gemini.shared.util.immutable.ScalaConverters._
import jsky.app.ot.userprefs.ui.{PreferencePanel, PreferenceDialog}
import jsky.catalog.{FieldDescAdapter, Catalog}
import jsky.util.Preferences

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.swing._
import scala.swing.event.{SelectionChanged, ButtonClicked}

import scalaz._
import Scalaz._

object ObsCatalogFrame extends Frame with PreferredSizeFrame {
  val instance = this

  title = "Gemini Science Program Database"

  val cqt = new ObsCatalogQueryTool(ObsCatalog.INSTANCE)
  contents = new MigPanel(LC().insets(0).fill().minWidth(1200.px)) {
    add(Component.wrap(cqt.queryPanel), CC().alignY(TopAlign).growX())
    add(cqt.buttonPanel, CC().newline().growX().gap(10.px, 10.px, 10.px, 10.px))
    add(Component.wrap(cqt.queryResults), CC().newline().growX().growY().pushY())
  }
  adjustSize(true)
}

protected object ObsQueryPreset {

  sealed trait ObsQueryPreset {
    val name: String
  }

  case object SaveNewPreset extends ObsQueryPreset {
    val name = "Save New Preset..."
  }

  case class SavedPreset(name: String) extends ObsQueryPreset {}

}

/**
  * Defines the user interface for querying an ObsCatalog.
  * @param catalog the catalog, for which a user interface component is being generated
  */
final class ObsCatalogQueryTool(catalog: Catalog) {
  import ObsQueryPreset._

  val PREF_KEY = classOf[ObsCatalogQueryTool].getName

  val queryPanel = new ObsCatalogQueryPanel(catalog, 6)
  val queryResults = new ObsCatalogQueryResultDisplay(new ObsCatalogQueryResult(ObsCatalog.INSTANCE.getConfigEntry, new java.util.Vector(), new java.util.Vector(), new java.util.ArrayList(), Array[FieldDescAdapter]()))
  val remote = new CheckBox("Include Remote Programs") {
        tooltip = "Check to include programs in the remote database in query results."
        selected = Preferences.get(PREF_KEY + ".remote", true)

        reactions += {
          case ButtonClicked(_) =>
            Preferences.set(PREF_KEY + ".remote", selected)
        }
      }

  val presetsCB = new ComboBox[ObsQueryPreset](List(SaveNewPreset)) with TextRenderer[ObsQueryPreset] {
    override def text(a: ObsQueryPreset): String = ~Option(a).map(_.name)

    listenTo(selection)

    reactions += {
      case SelectionChanged(e) if selection.item == SaveNewPreset =>
        queryPanel.storeSettings()
    }
  }

  val toolsButton = new Button("") {
    tooltip = "Preferences..."
    icon = new ImageIcon(getClass.getResource("/resources/images/eclipse/engineering.gif"))

    reactions += {
      case ButtonClicked(_) =>
        val dialog = new PreferenceDialog(List[PreferencePanel](BrowserPreferencesPanel.instance).asImList)
        dialog.show(ObsCatalogFrame.instance.peer, BrowserPreferencesPanel.instance)
    }
  }

  val queryButton: Button = {
    new Button("Query") {
      tooltip = "Start the Query"
      reactions += {
        case ButtonClicked(_) =>
          Future.apply(ObsCatalogHelper.query(queryPanel.getQueryArgs, ObsCatalog.newConfigEntry(), remote.selected)).onSuccess {
            case r =>
              queryResults.setQueryResult(r)
          }
      }
    }
  }

  val buttonPanel: Component = new MigPanel(LC().fill().insets(0)) {
      add(toolsButton, CC().alignX(RightAlign))
      add(presetsCB, CC().alignX(RightAlign).growY())
      add(remote, CC().alignX(RightAlign).pushX())
      add(queryButton, CC().alignX(RightAlign))
    }

}
