package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.common.path.Path
import com.keepit.model.{ Organization, Keep, Library, User }
import com.keepit.common.time._
import com.keepit.notify.info.{NotificationInfo, UsingDbSubset}
import com.keepit.social.{ BasicUser, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

trait NotificationEvent {

  val recipient: Recipient
  val time: DateTime
  val kind: NKind

}

object NotificationEvent {

  implicit val format = new OFormat[NotificationEvent] {

    override def reads(json: JsValue): JsResult[NotificationEvent] = {
      val kind = (json \ "kind").as[NKind]
      JsSuccess(json.as[NotificationEvent](kind.format.asInstanceOf[Reads[NotificationEvent]]))
    }

    override def writes(o: NotificationEvent): JsObject = {
      Json.obj(
        "kind" -> Json.toJson(o.kind)
      ) ++ o.kind.format.asInstanceOf[Writes[NotificationEvent]].writes(o).as[JsObject]
    }

  }

}

// todo missing, system-global notification
// todo missing, system notification

case class DepressedRobotGrumble(
    recipient: Recipient,
    time: DateTime,
    robotName: String,
    grumblingAbout: String,
    shouldGroup: Option[Boolean] = None) extends NotificationEvent {

  val kind = DepressedRobotGrumble

}

object DepressedRobotGrumble extends NotificationKind[DepressedRobotGrumble] {

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

  override def info(events: Set[DepressedRobotGrumble]): UsingDbSubset[NotificationInfo] = {
    def plural(phrase: String) = if (events.size == 1) phrase else plurals(phrase)

    UsingDbSubset() { subset =>
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
