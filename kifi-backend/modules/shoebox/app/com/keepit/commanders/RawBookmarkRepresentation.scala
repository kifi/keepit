package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.json.{ JsArray, JsObject, JsValue }
import com.keepit.model.{ URLFactory, Normalization }

case class RawBookmarkRepresentation(title: Option[String] = None, url: String, isPrivate: Boolean, canonical: Option[String] = None, openGraph: Option[String] = None)

class RawBookmarkFactory @Inject() (
    airbrake: AirbrakeNotifier) {

  def toRawBookmark(keepInfos: Seq[KeepInfo]): Seq[RawBookmarkRepresentation] =
    keepInfos map { k => RawBookmarkRepresentation(title = k.title, url = k.url, isPrivate = k.isPrivate) }

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
    val isPrivate = (json \ "isPrivate").asOpt[Boolean].getOrElse(true)
    val canonical = (json \ Normalization.CANONICAL.scheme).asOpt[String]
    val openGraph = (json \ Normalization.OPENGRAPH.scheme).asOpt[String]
    RawBookmarkRepresentation(title = title, url = url, isPrivate = isPrivate, canonical = canonical, openGraph = openGraph)
  }
}
