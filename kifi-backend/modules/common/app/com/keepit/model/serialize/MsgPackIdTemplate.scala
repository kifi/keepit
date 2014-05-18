package com.keepit.model.serialize

import com.keepit.common.db.Id

import org.msgpack.template._
import org.msgpack.unpacker.Unpacker
import org.msgpack.packer.Packer
import org.msgpack.MessageTypeException

class MsgPackIdTemplate[T] extends AbstractTemplate[Id[T]]{
  def write(packer: Packer, id: Id[T], required: Boolean): Unit = {
    if(id == null){
      if(required){
        throw new MessageTypeException("Attempted to write null")
      }
      packer.writeNil()
    }else{
      packer.write(id.id)
    }
  }


  def read(unpacker: Unpacker, to: Id[T], required: Boolean): Id[T] = {
    if(!required && unpacker.trySkipNil){
      return null
    }
    Id[T](unpacker.readLong())
  }
}
