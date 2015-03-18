package com.keepit.commanders

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.time._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.json._
import org.joda.time.DateTime

import scala.io.Source

/* RawBookmarkRepresentation is an input (not output) class for parsing JSON requests dealing with keeps.
 * The standard use case is parsing the request JSON, then keeping it.
 * Looking for an output class to represent keeps? Try KeepInfo.
 *
 * Do not trust isPrivate here. It's provided for legacy reasons only. Privacy is the responsibility of the libraryId.
 * LibraryId is purposely not in this, it should be provided separately.
 */
case class RawBookmarkRepresentation(
  title: Option[String] = None,
  url: String,
  isPrivate: Option[Boolean],
  canonical: Option[String] = None,
  openGraph: Option[String] = None,
  keptAt: Option[DateTime] = None,
  sourceAttribution: Option[SourceAttribution] = None)

object RawBookmarkRepresentation {
  case class RawBookmarkRepresentationWithoutAttribution(title: Option[String], url: String, isPrivate: Option[Boolean], canonical: Option[String], openGraph: Option[String], keptAt: Option[DateTime])

  implicit val helperFormat = Json.format[RawBookmarkRepresentationWithoutAttribution]

  // NOTE: No attemp to parse the trait SourceAttribution
  implicit val reads = new Reads[RawBookmarkRepresentation] {
    def reads(js: JsValue): JsResult[RawBookmarkRepresentation] = {
      val x = js.as[RawBookmarkRepresentationWithoutAttribution]
      JsSuccess(RawBookmarkRepresentation(x.title, x.url, x.isPrivate, x.canonical, x.openGraph, x.keptAt, None))
    }
  }

  // NOTE: No attemp to write the trait SourceAttribution
  implicit val writes = new Writes[RawBookmarkRepresentation] {
    def writes(keep: RawBookmarkRepresentation): JsValue = {
      val tmp = RawBookmarkRepresentationWithoutAttribution(keep.title, keep.url, keep.isPrivate, keep.canonical, keep.openGraph, keep.keptAt)
      Json.toJson(tmp)
    }
  }
}

@Singleton
class RawBookmarkFactory @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock) {

  @deprecated("KeepInfo was never intended to be an input value class. Use RawBookmarkRepresentation.", "2014-08-29")
  def toRawBookmark(keepInfos: Seq[KeepInfo]): Seq[RawBookmarkRepresentation] =
    keepInfos map { k => RawBookmarkRepresentation(title = k.title, url = k.url, isPrivate = Some(k.isPrivate), keptAt = k.createdAt) }

  private[commanders] def getBookmarkJsonObjects(value: JsValue): Seq[JsObject] = value match {
    case JsArray(elements) => elements.map(getBookmarkJsonObjects).flatten
    case json: JsObject if json.keys.contains("children") => getBookmarkJsonObjects(json \ "children")
    case json: JsObject if json.keys.contains("bookmarks") => getBookmarkJsonObjects(json \ "bookmarks")
    case json: JsObject => Seq(json)
    case _ =>
      airbrake.notify(s"error parsing bookmark import json $value")
      Seq()
  }

  def toRawBookmarks(value: JsValue): Seq[RawBookmarkRepresentation] = getBookmarkJsonObjects(value) map toRawBookmark

  def toRawBookmark(json: JsObject): RawBookmarkRepresentation = {
    val title = (json \ "title").asOpt[String].map(_.take(URLFactory.MAX_URL_SIZE))
    val url = (json \ "url").asOpt[String].map(_.take(URLFactory.MAX_URL_SIZE)).getOrElse(throw new Exception(s"json $json did not have a url"))
    val isPrivate = (json \ "isPrivate").asOpt[Boolean]
    val canonical = (json \ Normalization.CANONICAL.scheme).asOpt[String]
    val openGraph = (json \ Normalization.OPENGRAPH.scheme).asOpt[String]
    RawBookmarkRepresentation(title = title, url = url, isPrivate = isPrivate, canonical = canonical, openGraph = openGraph, Some(clock.now), None)
  }
}
