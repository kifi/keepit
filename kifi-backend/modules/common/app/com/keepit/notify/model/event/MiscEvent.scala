package com.keepit.notify.model.event

import com.keepit.notify.info.{ NotificationInfo, UsingDbView }
import com.keepit.notify.model.{ Recipient, NotificationKind }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.time._

trait DepressedRobotGrumbleImpl extends NotificationKind[DepressedRobotGrumble] {

  override val name: String = "depressed_robot_grumble"

  override implicit val format = (
    (__ \ "recipient").format[Recipient] and
    (__ \ "time").format[DateTime] and
    (__ \ "robotName").format[String] and
    (__ \ "grumblingAbout").format[String] and
    (__ \ "shouldGroup").formatNullable[Boolean]
  )(DepressedRobotGrumble.apply, unlift(DepressedRobotGrumble.unapply))

  override def shouldGroupWith(newEvent: DepressedRobotGrumble, existingEvents: Set[DepressedRobotGrumble]): Boolean = newEvent.shouldGroup.getOrElse(false)

  private val plurals = Map(
    "A robot" -> "Some robots",
    "He" -> "They"
  )

  def englishJoin(items: Seq[String]) = items.length match {
    case 0 => throw new IllegalArgumentException("Items must have at least one element")
    case 1 => items.head
    case 2 => items.mkString(" and ")
    case n => items.init.mkString(", ") + ", and " + items.last
  }

  override def info(events: Set[DepressedRobotGrumble]): UsingDbView[NotificationInfo] = {
    def plural(phrase: String) = if (events.size == 1) phrase else plurals(phrase)

    UsingDbView() { subset =>
      NotificationInfo(
        url = "http://goo.gl/PqN7Cs",
        imageUrl = "http://i.imgur.com/qs8QofA.png",
        title = s"${plural("A robot")} just grumbled! ${plural("He")} must be depressed...",
        body = s"${englishJoin(events.toSeq.map(_.robotName))} just grumbled about ${englishJoin(events.toSeq.map(_.grumblingAbout))}",
        linkText = "Organize and share knowledge with Kifi!"
      )
    }
  }

}
