package com.keepit.model.serialize

import org.msgpack.annotation.Message
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.NormalizedURI
import play.api.libs.functional.syntax._
import play.api.libs.json._

@Message
case class UriIdAndSeq(var id: Id[NormalizedURI], var seq: SequenceNumber[NormalizedURI]) {
  def this() = this(null, null)
}

// for dev mode until ScalaMessagePack is fixed
object UriIdAndSeq {
  private implicit val idformat = Id.format[NormalizedURI]
  private implicit val seqFormat = SequenceNumber.format[NormalizedURI]
  implicit val format = Json.format[UriIdAndSeq]
}



@Message
case class UriIdAndSeqBatch(var batch: Seq[UriIdAndSeq]) {
  def this() = this(null)
}
