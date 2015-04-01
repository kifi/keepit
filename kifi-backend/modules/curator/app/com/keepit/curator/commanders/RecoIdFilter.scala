package com.keepit.curator.commanders

import com.keepit.common.logging.Logging
import com.keepit.search.util.LongSetIdFilter

import scala.collection.mutable.ArrayBuffer

trait RecoIdFilter[R] extends Logging {
  type FilterResult = (Seq[R], String) // accepted recos, and new context string

  def filter(recos: Seq[R], context: Option[String])(idFunc: R => Long): FilterResult = {
    val idFilter = new LongSetIdFilter()
    val exclude = idFilter.fromBase64ToSet(context.getOrElse(""))
    val accepted = recos.filter { r => !exclude.contains(idFunc(r)) }
    val newSet = exclude ++ accepted.map { idFunc(_) }
    val newContext = idFilter.fromSetToBase64(newSet)
    (accepted, newContext)
  }

  def take(recos: Seq[R], context: Option[String], limit: Int)(idFunc: R => Long): FilterResult = {
    require(recos.size == recos.map { idFunc(_) }.distinct.size, "recos should contain distinct ids")
    val idFilter = new LongSetIdFilter()
    val exclude = idFilter.fromBase64ToSet(context.getOrElse(""))
    val buf = new ArrayBuffer[R]()
    var i = 0
    val iter = recos.iterator
    while (iter.hasNext && i < limit) {
      val item = iter.next()
      val id = idFunc(item)
      if (!exclude.contains(id)) {
        buf.append(item)
        i += 1
      }
    }
    val newSet = exclude ++ buf.map { idFunc(_) }.toSet
    val newContext = idFilter.fromSetToBase64(newSet)
    (buf, newContext)
  }
}
