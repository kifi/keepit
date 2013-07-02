package com.keepit.serializer

import com.keepit.search.PersonalSearchHit

import play.api.libs.json._

class PersonalSearchHitSerializer extends Writes[PersonalSearchHit] {
  def writes(hit: PersonalSearchHit): JsValue = {

    var json = Json.obj(
      "title" -> hit.title,
      "url" -> hit.url
    )

    json = addMatches(json, hit)
    json = addCollections(json, hit)
    json
  }

  private def addMatches(json: JsObject, hit: PersonalSearchHit): JsObject = {
    var matches = Json.obj()
    matches = addTitleMatches(matches, hit)
    matches = addUrlMatches(matches, hit)

    if (matches.keys.size == 0) json
    else json + ("matches" -> matches)
  }

  private def addTitleMatches(json: JsObject, hit: PersonalSearchHit): JsObject = {
    if (hit.titleMatches.nonEmpty) {
      json + ("title" -> JsArray(hit.titleMatches.map(h => Json.arr(h._1, (h._2 - h._1)))))
    } else json
  }
  private def addUrlMatches(json: JsObject, hit: PersonalSearchHit): JsObject = {
    if (hit.urlMatches.nonEmpty) {
      json + ("url" -> JsArray(hit.urlMatches.map(h => Json.arr(h._1, (h._2 - h._1)))))
    } else json
  }

  private def addCollections(json: JsObject, hit: PersonalSearchHit): JsObject = {
    hit.collections match {
      case Some(collections) =>
        json + ("collecitons" -> JsArray(collections.map(id => JsString(id.id))))
      case None => json
    }
  }
}

object PersonalSearchHitSerializer {
  implicit val hitSerializer = new PersonalSearchHitSerializer
}
