package com.keepit.model

import com.keepit.common.db._
import org.joda.time.DateTime
import play.api.libs.json.{ JsArray, JsObject, JsValue }
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier

case class RawKeep(
    id: Option[Id[RawKeep]] = None,
    userId: Id[User],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    url: String,
    title: Option[String] = None,
    isPrivate: Boolean = true,
    importId: Option[String] = None,
    source: KeepSource,
    installationId: Option[ExternalId[KifiInstallation]] = None,
    originalJson: Option[JsValue] = None,
    state: State[RawKeep] = RawKeepStates.ACTIVE,
    tagIds: Option[String] = None,
    libraryId: Option[Id[Library]],
    createdDate: Option[DateTime] = None) extends Model[RawKeep] {
  def withId(id: Id[RawKeep]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

class RawKeepFactory @Inject() (airbrake: AirbrakeNotifier) {

  private def getBookmarkJsonObjects(value: JsValue): Seq[JsObject] = value match {
    case JsArray(elements) => elements.map(getBookmarkJsonObjects).flatten
    case json: JsObject if json.keys.contains("children") => getBookmarkJsonObjects(json \ "children")
    case json: JsObject if json.keys.contains("bookmarks") => getBookmarkJsonObjects(json \ "bookmarks")
    case json: JsObject => Seq(json)
    case _ =>
      airbrake.notify(s"error parsing bookmark import json $value")
      Seq()
  }

  def toRawKeep(userId: Id[User], source: KeepSource, value: JsValue, importId: Option[String] = None, installationId: Option[ExternalId[KifiInstallation]] = None, libraryId: Option[Id[Library]]): Seq[RawKeep] = getBookmarkJsonObjects(value) map { json =>
    val title = (json \ "title").asOpt[String]
    val url = (json \ "url").asOpt[String].getOrElse(throw new Exception(s"json $json did not have a url"))
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)
    val canonical = (json \ Normalization.CANONICAL.scheme).asOpt[String]
    val openGraph = (json \ Normalization.OPENGRAPH.scheme).asOpt[String]
    RawKeep(userId = userId, title = title, url = url, isPrivate = isPrivate, importId = importId, source = source, originalJson = Some(json), installationId = installationId, libraryId = libraryId)
  }
}

object RawKeepStates extends States[RawKeep] {
  val IMPORTING = State[RawKeep]("importing")
  val IMPORTED = State[RawKeep]("imported")
  val FAILED = State[RawKeep]("failed")
}
