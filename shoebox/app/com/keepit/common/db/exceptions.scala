package com.keepit.common.db

class ElementWithExternalIdNotFoundException[T](id: ExternalId[T], m: Manifest[T])
  extends NoSuchElementException("can't find external id %s for element of type %s".format(id.toString(), m.toString()))

class ElementWithInternalIdNotFoundException[T](id: Id[T], m: Manifest[T])
  extends NoSuchElementException("can't find internal id %s for element of type %s".format(id.toString(), m.toString()))


object NotFoundException {

  def apply[T](id: ExternalId[T])(implicit m: Manifest[T]) =
    new ElementWithExternalIdNotFoundException(id, m)

  def apply[T](id: Id[T])(implicit m: Manifest[T]) =
    new ElementWithInternalIdNotFoundException(id, m)

}
