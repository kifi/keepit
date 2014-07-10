package com.keepit.model.serialize

import com.keepit.common.db.SequenceNumber

import org.msgpack.template._
import org.msgpack.unpacker.Unpacker
import org.msgpack.packer.Packer

class MsgPackSequenceNumberTemplate[T] extends AbstractTemplate[SequenceNumber[T]] {
  def write(packer: Packer, seq: SequenceNumber[T], required: Boolean): Unit = {
    if (seq == null) throw new NullPointerException("can't write a null seq, Only Option[Seq[T]] may have a not required value")
    packer.write(seq.value)
  }

  def read(unpacker: Unpacker, to: SequenceNumber[T], required: Boolean): SequenceNumber[T] = {
    SequenceNumber[T](unpacker.readLong())
  }
}
