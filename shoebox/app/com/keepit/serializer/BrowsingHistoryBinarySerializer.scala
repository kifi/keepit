package com.keepit.serializer

import com.keepit.model.BrowsingHistory
import play.api.libs.json._
import com.keepit.common.time._
import com.keepit.common.strings._
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.model.BrowsingHistory
import play.api.libs.json.JsNumber
import com.keepit.common.db._
import com.keepit.model.User

trait BinaryFormat {

}

class BrowsingHistoryBinarySerializer extends BinaryFormat {

  def writes(history: BrowsingHistory): Array[Byte] = {
    val json = historyJsonWrites(history).toString.getBytes(ENCODING)
    val filter = history.filter
    val outStream = new ByteArrayOutputStream(json.size + filter.size + 2)
    outStream.write(json.size)
    outStream.write(filter.size)
    outStream.write(json)
    outStream.write(filter)
    outStream.toByteArray
  }

  def historyJsonWrites(history: BrowsingHistory): JsObject =
    JsObject(List(
      "id"  -> history.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> JsString(history.createdAt.toStandardTimeString),
      "updatedAt" -> JsString(history.updatedAt.toStandardTimeString),
      "state" -> JsString(history.state.value),
      "userId"  -> JsNumber(history.userId.id),
      "tableSize" -> JsNumber(history.tableSize),
      "numHashFuncs" -> JsNumber(history.numHashFuncs),
      "minHits" -> JsNumber(history.minHits),
      "updatesCount"  -> JsNumber(history.updatesCount)
    )
    )

  def reads(bytes: Array[Byte]): BrowsingHistory = {
    val inStream = new ByteArrayInputStream(bytes)
    val jsonSize = inStream.read()
    val filterSize = inStream.read()

    val jsonBytes = new Array[Byte](jsonSize)
    assume(jsonSize == inStream.read(jsonBytes, 0, jsonSize), "Incorrectly sized JSON input in BrowsingHistory parsing")

    val filterBytes = new Array[Byte](filterSize)
    assume(filterSize == inStream.read(filterBytes, 0, filterSize), "Incorrectly sized filter input in BrowsingHistory parsing")

    val historyNoFilter = historyJsonReads(Json.parse(new String(jsonBytes, ENCODING)))

    historyNoFilter.copy(filter = filterBytes)
  }

  def historyJsonReads(json: JsValue): BrowsingHistory =
    BrowsingHistory(
      id = (json \ "id").asOpt[Long].map(Id[BrowsingHistory](_)),
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      updatedAt = parseStandardTime((json \ "updatedAt").as[String]),
      state = State[BrowsingHistory]((json \ "state").as[String]),
      userId = Id[User]((json \ "userId").as[Long]),
      tableSize = (json \ "tableSize").as[Int],
      filter = Array.fill[Byte]((json \ "tableSize").as[Int])(0),
      numHashFuncs = (json \ "numHashFuncs").as[Int],
      minHits = (json \ "minHits").as[Int],
      updatesCount = (json \ "updatesCount").as[Int]
    )

}
