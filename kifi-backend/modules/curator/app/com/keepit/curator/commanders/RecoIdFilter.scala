package com.keepit.curator.commanders

import com.keepit.search.util.LongSetIdFilter

trait RecoIdFilter[R] {
  type FilterResult = (Seq[R], String) // accepted recos, and new context string

  def filter(recos: Seq[R], context: Option[String])(idFunc: R => Long): FilterResult = {
    val idFilter = new LongSetIdFilter()
    val exclude = idFilter.fromBase64ToSet(context.getOrElse(""))
    val accepted = recos.filter { r => !exclude.contains(idFunc(r)) }
    val newSet = exclude ++ accepted.map { idFunc(_) }
    val newContext = idFilter.fromSetToBase64(newSet)
    (accepted, newContext)
  }
}
