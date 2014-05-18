package com.keepit.model.serialize

import org.msgpack.annotation.Message
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.NormalizedURI

@Message
class UriIdAndSeq {
  var id: Id[NormalizedURI] = Id(-1)
  var seq: SequenceNumber[NormalizedURI] = SequenceNumber(-1)

}

object UriIdAndSeq {
  def apply(id: Id[NormalizedURI], seq: SequenceNumber[NormalizedURI]): UriIdAndSeq = {
    val model = new UriIdAndSeq()
    model.id = id
    model.seq = seq
    model
  }
}

@Message
class UriIdAndSeqBatch {
  var batch: Seq[UriIdAndSeq] = Nil
}

object UriIdAndSeqBatch {
  def apply(batch: Seq[UriIdAndSeq]): UriIdAndSeqBatch = {
    val model = new UriIdAndSeqBatch()
    model.batch = batch
    model
  }
}

