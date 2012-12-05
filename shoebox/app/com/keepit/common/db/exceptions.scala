package com.keepit.common.db

class ElementWithExternalIdNotFoundException[T](m: Manifest[T], id: ExternalId[T])
  extends NoSuchElementException("can't find %s with id %s".format(m, id))

class ElementWithInternalIdNotFoundException[T](m: Manifest[T], id: Id[T])
  extends NoSuchElementException("can't find %s with id %s".format(m, id))

class ElementWithInternalIdsNotFoundException[T,U,V](c: Class[T], m1: Manifest[U], id1: Id[U], m2: Manifest[V], id2: Id[V])
  extends NoSuchElementException("can't find %s with %s id %s and %s id %s".format(c.getName(), m1, id1, m2, id2))

class ElementWithInternalIdAndExternalIdNotFoundException[T,U,V](c: Class[T], m1: Manifest[U], id1: Id[U], m2: Manifest[V], id2: ExternalId[V])
  extends NoSuchElementException("can't find %s with %s id %s and %s id %s".format(c.getName(), m1, id1, m2, id2))

object NotFoundException {

  def apply[T](id: ExternalId[T])(implicit m: Manifest[T]) =
    new ElementWithExternalIdNotFoundException(m, id)

  def apply[T](id: Id[T])(implicit m: Manifest[T]) =
    new ElementWithInternalIdNotFoundException(m, id)

  def apply[T,U,V](c: Class[T], id1: Id[U], id2: Id[V])(implicit m1: Manifest[U], m2: Manifest[V]) =
    new ElementWithInternalIdsNotFoundException(c, m1, id1, m2, id2)

  def apply[T,U,V](c: Class[T], id1: Id[U], id2: ExternalId[V])(implicit m1: Manifest[U], m2: Manifest[V]) =
    new ElementWithInternalIdAndExternalIdNotFoundException(c, m1, id1, m2, id2)

}
