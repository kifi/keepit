package com.keepit.curator.feedback

import play.api.libs.json._

// Immutable operations. Optimized for thread-safe.
case class ByteArrayCounter(bytes: Array[Byte]) {

  private def unsignedbyte2Int(b: Byte): Int = (b & 0xFF)
  private def isValidIndex(idx: Int): Boolean = idx >= 0 && idx < bytes.size
  private def canStoreCount(x: Int): Boolean = x >= 0 && x <= ByteArrayCounter.MAX_COUNT

  def canIncrement(idx: Int, delta: Int = 1): Boolean = {
    require(delta >= 1 && isValidIndex(idx), s"invalid parameters: idx = ${idx}, delta = ${delta}")
    (get(idx) + delta) <= ByteArrayCounter.MAX_COUNT
  }

  def increment(idx: Int, delta: Int = 1): ByteArrayCounter = {
    require(delta >= 1 && isValidIndex(idx), s"invalid parameters: idx = ${idx}, delta = ${delta}")
    if (canIncrement(idx, delta)) {
      val x = get(idx) + delta
      assert(canStoreCount(x))
      set(idx, x)
    } else throw new Exception(s"cannot increment ByteArrayCounter: current value = ${get(idx)}, incre = ${delta}")
  }

  def get(idx: Int): Int = {
    require(isValidIndex(idx))
    unsignedbyte2Int(bytes(idx))
  }

  def set(idx: Int, value: Int): ByteArrayCounter = {
    require(canStoreCount(value) && isValidIndex(idx), s"invalid parameters: idx = ${idx}, value = ${value}")
    val arr = toArray()
    arr(idx) = value
    ByteArrayCounter.fromArray(arr)
  }

  def setMultiple(indexes: Seq[Int], values: Seq[Int]): ByteArrayCounter = {
    require(indexes.forall(isValidIndex(_)) && values.forall(canStoreCount(_)) && indexes.size == values.size)
    val arr = toArray()
    (indexes zip values).foreach { case (i, x) => arr(i) = x }
    ByteArrayCounter.fromArray(arr)
  }

  def toArray(): Array[Int] = Array.tabulate(bytes.size) { i => get(i) }
}

object ByteArrayCounter {
  val MAX_COUNT = 255

  def empty(n: Int): ByteArrayCounter = {
    ByteArrayCounter(new Array[Byte](n))
  }

  def fromArray(xs: Array[Int]): ByteArrayCounter = {
    require(xs.forall(x => x >= 0 && x <= MAX_COUNT))
    ByteArrayCounter(xs.map { x => x.toByte })
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
