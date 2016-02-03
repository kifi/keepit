package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.model._
import org.joda.time.DateTime
import play.api.libs.json._

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
    canonical: Option[String] = None,
    openGraph: Option[String] = None,
    keptAt: Option[DateTime] = None,
    sourceAttribution: Option[RawSourceAttribution] = None, // clients can't provide this. probably a bad idea to have here
    note: Option[String] = None // supports strings "Formatted like #this"
    ) {
  def noteFormattedLikeOurNotes: Option[String] = note.map(n => Hashtags.formatExternalNote(n)).filter(_.nonEmpty)
}

object RawBookmarkRepresentation {
  case class RawBookmarkRepresentationWithoutAttribution(title: Option[String], url: String, canonical: Option[String], openGraph: Option[String], keptAt: Option[DateTime], note: Option[String])

  implicit val helperFormat = Json.format[RawBookmarkRepresentationWithoutAttribution]

  // NOTE: No attemp to parse the trait SourceAttribution
  implicit val reads = new Reads[RawBookmarkRepresentation] {
    def reads(js: JsValue): JsResult[RawBookmarkRepresentation] = {
      val x = js.as[RawBookmarkRepresentationWithoutAttribution]
      JsSuccess(RawBookmarkRepresentation(x.title, x.url, x.canonical, x.openGraph, x.keptAt, None))
    }
  }
}

@Singleton
class RawBookmarkFactory @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock) {

  private[commanders] def getBookmarkJsonObjects(value: JsValue): Seq[JsObject] = value match {
    case JsArray(elements) => elements.flatMap(getBookmarkJsonObjects)
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
    val canonical = (json \ Normalization.CANONICAL.scheme).asOpt[String]
    val openGraph = (json \ Normalization.OPENGRAPH.scheme).asOpt[String]
    RawBookmarkRepresentation(title = title, url = url, canonical = canonical, openGraph = openGraph, keptAt = Some(clock.now), sourceAttribution = None, note = None)
  }
}

