package com.keepit.model.serialize

import com.keepit.common.db.SequenceNumber

import org.msgpack.template._
import org.msgpack.unpacker.Unpacker
import org.msgpack.packer.Packer
import org.msgpack.MessageTypeException

class MsgPackSequenceNumberTemplate[T] extends AbstractTemplate[SequenceNumber[T]]{
  def write(packer: Packer, seq: SequenceNumber[T], required: Boolean): Unit = {
    if(seq == null){
      if(required){
        throw new MessageTypeException("Attempted to write null")
      }
      packer.writeNil()
    }else{
      packer.write(seq.value)
    }
  }


  def read(unpacker: Unpacker, to: SequenceNumber[T], required: Boolean): SequenceNumber[T] = {
    if(!required && unpacker.trySkipNil){
      return null
    }
    SequenceNumber[T](unpacker.readLong())
  }
}
