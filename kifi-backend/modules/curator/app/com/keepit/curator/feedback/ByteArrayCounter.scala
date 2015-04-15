package com.keepit.curator.feedback

import play.api.libs.json._

case class ByteArrayCounter(bytes: Array[Byte]) {

  private def unsignedbyte2Int(b: Byte): Int = (b & 0xFF).toInt
  private def isValidIndex(idx: Int): Boolean = idx >= 0 && idx < bytes.size
  private def canStoreCount(x: Int): Boolean = x >= 0 && x <= ByteArrayCounter.MAX_COUNT

  def canIncrement(idx: Int, delta: Int = 1): Boolean = {
    require(delta >= 1 && isValidIndex(idx), s"invalid parameters: idx = ${idx}, delta = ${delta}")
    (get(idx) + delta) <= ByteArrayCounter.MAX_COUNT
  }

  def increment(idx: Int, delta: Int = 1): Unit = {
    require(delta >= 1 && isValidIndex(idx), s"invalid parameters: idx = ${idx}, delta = ${delta}")
    if (canIncrement(idx, delta)) {
      val x = get(idx) + delta
      assert(canStoreCount(x))
      bytes(idx) = x.toByte
    } else throw new Exception(s"cannot increment ByteArrayCounter: current value = ${get(idx)}, incre = ${delta}")
  }

  def get(idx: Int): Int = {
    require(isValidIndex(idx))
    unsignedbyte2Int(bytes(idx))
  }

  def set(idx: Int, value: Int): Unit = {
    require(canStoreCount(value) && isValidIndex(idx), s"invalid parameters: idx = ${idx}, value = ${value}")
    bytes(idx) = value.toByte
  }

  def toArray(): Array[Int] = (0 until bytes.size).map { i => get(i) }.toArray
}

object ByteArrayCounter {
  val MAX_COUNT = 255

  def empty(n: Int): ByteArrayCounter = {
    ByteArrayCounter(new Array[Byte](n))
  }

  def fromArray(xs: Array[Int]): ByteArrayCounter = {
    val counter = empty(xs.size)
    (0 until xs.size).foreach { i => counter.set(i, xs(i)) }
    counter
  }

  implicit val reads = new Reads[ByteArrayCounter] {
    def reads(js: JsValue): JsResult[ByteArrayCounter] = {
      val values = js.as[JsArray].value.map { _.as[Int] }.toArray
      JsSuccess(fromArray(values))
    }
  }

  implicit val writes = new Writes[ByteArrayCounter] {
    def writes(counter: ByteArrayCounter): JsValue = {
      Json.toJson(counter.toArray())
    }
  }

}
