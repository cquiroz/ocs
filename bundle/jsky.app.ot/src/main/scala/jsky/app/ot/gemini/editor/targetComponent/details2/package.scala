package jsky.app.ot.gemini.editor.targetComponent

import java.beans.{PropertyChangeListener, PropertyChangeEvent}

import edu.gemini.horizons.api.HorizonsQuery.ObjectType
import edu.gemini.spModel.target.system.{CoordinateParam, NamedTarget, NonSiderealTarget}
import edu.gemini.spModel.target.system.ITarget.Tag
import jsky.app.ot.ui.util.FlatButtonUtil
import jsky.util.gui.{ TextBoxWidget, TextBoxWidgetWatcher }

import java.awt.event.{ActionEvent, ActionListener}
import javax.swing.BorderFactory._
import javax.swing.JButton
import javax.swing.border.Border

import scala.language.implicitConversions
import scalaz._, Scalaz._, scalaz.concurrent.Task

package object details2 {

  /** Create a titled border with inner and outer padding. */
  def titleBorder(title: String): Border =
    createCompoundBorder(
      createEmptyBorder(2,2,2,2),
      createCompoundBorder(
        createTitledBorder(title),
        createEmptyBorder(2,2,2,2)))

  def watcher(f: String => Unit) = new TextBoxWidgetWatcher {
    override def textBoxKeyPress(tbwe: TextBoxWidget): Unit = textBoxAction(tbwe)
    override def textBoxAction(tbwe: TextBoxWidget): Unit = f(tbwe.getValue)
  }

  def searchButton(doSearch: => Unit): JButton =
    FlatButtonUtil.create("eclipse/search.gif") <| { b =>
      b.addActionListener(new ActionListener() {
        override def actionPerformed(e: ActionEvent) = doSearch
      })
    }

  implicit class NonSiderealTargetOps(nst: NonSiderealTarget) {
    def getHorizonsObjectType: ObjectType =
      ObjectType.values()(nst.getHorizonsObjectTypeOrdinal)
  }

  implicit class ITargetTagOps(tag: Tag) {
    def toHorizonsObjectType: Option[ObjectType] =
      Some(tag) collect {
        case Tag.JPL_MINOR_BODY   => ObjectType.COMET
        case Tag.MPC_MINOR_PLANET => ObjectType.MINOR_BODY
        case Tag.NAMED            => ObjectType.MAJOR_BODY
      }
    def unsafeToHorizonsObjectType: ObjectType =
      toHorizonsObjectType.getOrElse(throw new NoSuchElementException("No Horizons object type for target tag " + tag))
  }

  implicit class SolarObjectOps(obj: NamedTarget.SolarObject) {
    def objectType = ObjectType.MAJOR_BODY
  }

  implicit class CoordinateParamOps(p: CoordinateParam) {
    def setOrZero(d: java.lang.Double): Unit =
      p.setValue(if (d == null) 0.0 else d.doubleValue)
  }

  def forkSwingWorker[A <: AnyRef](constructImpl: => A)(finishedImpl: Throwable \/ A => Unit): Unit =
    Task(constructImpl).runAsync(finishedImpl)

  implicit def F2ActionlListener(f: ActionEvent => Unit): ActionListener =
    new ActionListener { def actionPerformed(e: ActionEvent): Unit = f(e) }

  implicit def F2PropertyChangeListener(f: PropertyChangeEvent => Unit): PropertyChangeListener =
    new PropertyChangeListener { def propertyChange(evt: PropertyChangeEvent): Unit = f(evt) }

  // Turn an A @ Option[B] into an A @> B given a default value for B
  implicit class OptionsLensOps[A, B](lens: A @> Option[B]) {
    def orZero(zero: B): A @> B =
      lens.xmapB(_.getOrElse(zero))(b => (b != zero).option(b))
  }

}
