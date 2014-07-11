package com.keepit.model.serialize

import com.keepit.common.db.Id

import org.msgpack.template._
import org.msgpack.unpacker.Unpacker
import org.msgpack.packer.Packer
import org.msgpack.MessageTypeException

class MsgPackIdTemplate[T] extends AbstractTemplate[Id[T]] {
  def write(packer: Packer, id: Id[T], required: Boolean): Unit = {
    if (id == null) throw new NullPointerException("can't write a null id, Only Option[Id[T]] may have a not required value")
    packer.write(id.id)
  }

  def read(unpacker: Unpacker, to: Id[T], required: Boolean): Id[T] = {
    Id[T](unpacker.readLong())
  }
}

class MsgPackOptIdTemplate[T] extends AbstractTemplate[Option[Id[T]]] {
  def write(packer: Packer, idOpt: Option[Id[T]], required: Boolean): Unit = {
    if (idOpt == null || idOpt.isEmpty) {
      if (required) throw new MessageTypeException("Attempted to write null")
      packer.writeNil()
    } else {
      packer.write(idOpt.get.id)
    }
  }

  def read(unpacker: Unpacker, to: Option[Id[T]], required: Boolean): Option[Id[T]] = {
    if (!required && unpacker.trySkipNil) None else Some(Id[T](unpacker.readLong()))
  }
}
