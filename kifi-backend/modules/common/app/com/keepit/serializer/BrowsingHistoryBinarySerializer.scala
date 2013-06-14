package com.keepit.serializer

import com.keepit.model.BrowsingHistory
import play.api.libs.json._
import com.keepit.common.time._
import com.keepit.common.strings._
import java.io.{DataOutputStream, DataInputStream, ByteArrayInputStream, ByteArrayOutputStream}
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import com.keepit.model.BrowsingHistory
import play.api.libs.json.JsNumber
import com.keepit.common.db._
import com.keepit.model.User
import com.keepit.common.logging.Logging
import org.joda.time.DateTime

class BrowsingHistoryBinarySerializer extends BinaryFormat[BrowsingHistory] with Logging {

  def writes(history: BrowsingHistory): Array[Byte] = {
    val json = historyJsonWrites(history).toString.getBytes(UTF8)
    val filter = history.filter

    assume(filter.size == history.tableSize, "Filter is not tableSize: %s != %s".format(filter.size, history.tableSize))
    val byteStream = new ByteArrayOutputStream(json.size + filter.size + 8)
    val outStream = new DataOutputStream(byteStream)

    outStream.writeInt(json.size)
    outStream.writeInt(filter.size)
    outStream.write(json)
    outStream.write(filter)
    outStream.close()

    byteStream.toByteArray
  }

  def historyJsonWrites(history: BrowsingHistory): JsObject =
    JsObject(List(
      "id"  -> history.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> Json.toJson(history.createdAt),
      "updatedAt" -> Json.toJson(history.updatedAt),
      "state" -> JsString(history.state.value),
      "userId"  -> JsNumber(history.userId.id),
      "tableSize" -> JsNumber(history.tableSize),
      "numHashFuncs" -> JsNumber(history.numHashFuncs),
      "minHits" -> JsNumber(history.minHits),
      "updatesCount"  -> JsNumber(history.updatesCount)
    )
    )

  def reads(bytes: Array[Byte]): BrowsingHistory = {
    val inStream = new DataInputStream(new ByteArrayInputStream(bytes))
    val jsonSize = inStream.readInt()
    val filterSize = inStream.readInt()

    assume(filterSize > 0, "Filter is empty!")
    assume(jsonSize > 0, "JSON is empty!")

    val jsonBytes = new Array[Byte](jsonSize)
    assume(jsonSize == inStream.read(jsonBytes, 0, jsonSize), "Incorrectly sized JSON input in BrowsingHistory parsing")

    val filterBytes = new Array[Byte](filterSize)
    val readBytes = inStream.read(filterBytes, 0, filterSize)
    assume(filterSize == readBytes, "Incorrectly sized filter input in BrowsingHistory parsing. %s != %s".format(filterSize, readBytes))

    val historyNoFilter = historyJsonReads(Json.parse(new String(jsonBytes, UTF8)))

    assume(historyNoFilter.tableSize == filterSize, "Filter size is not tableSize, %s != %s".format(historyNoFilter.tableSize, filterSize))

    historyNoFilter.copy(filter = filterBytes)
  }

  def historyJsonReads(json: JsValue): BrowsingHistory =
    BrowsingHistory(
      id = (json \ "id").asOpt[Long].map(Id[BrowsingHistory](_)),
      createdAt = (json \ "createdAt").as[DateTime],
      updatedAt = (json \ "updatedAt").as[DateTime],
      state = State[BrowsingHistory]((json \ "state").as[String]),
      userId = Id[User]((json \ "userId").as[Long]),
      tableSize = (json \ "tableSize").as[Int],
      filter = Array.fill[Byte]((json \ "tableSize").as[Int])(0),
      numHashFuncs = (json \ "numHashFuncs").as[Int],
      minHits = (json \ "minHits").as[Int],
      updatesCount = (json \ "updatesCount").as[Int]
    )

}

object BrowsingHistoryBinarySerializer {
  implicit val browsingHistoryBinarySerializer = new BrowsingHistoryBinarySerializer
}
