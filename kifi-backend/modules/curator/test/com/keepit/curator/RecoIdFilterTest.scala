package com.keepit.curator

import com.keepit.curator.commanders.RecoIdFilter
import com.keepit.search.util.LongSetIdFilter
import org.specs2.mutable._

class RecoIdFilterTest extends Specification {

  "reco id filter" should {

    "filter" in {

      class LongFilter extends RecoIdFilter[Long]

      val filter = new LongFilter
      val coder = new LongSetIdFilter
      val idFunc = (x: Long) => x

      val (ids0, context0) = filter.filter(List(), None)(idFunc)
      ids0 === List()
      coder.fromBase64ToSet(context0) === Set()

      val (ids1, context1) = filter.filter(List(1L, 2L, 3L), None)(idFunc)
      ids1 === List(1, 2, 3)
      coder.fromBase64ToSet(context1) === Set(1, 2, 3)

      val (ids2, context2) = filter.filter(List(1L, 2L, 4L, 5L, 6L), Some(context1))((idFunc))
      ids2 === List(4, 5, 6)
      coder.fromBase64ToSet(context2) === Set(1, 2, 3, 4, 5, 6)

    }

    "take correctly" in {

      class LongFilter extends RecoIdFilter[Long]

      val filter = new LongFilter
      val coder = new LongSetIdFilter
      val idFunc = (x: Long) => x

      val (ids0, context0) = filter.take(List(), None, limit = 5)(idFunc)
      ids0 === List()
      coder.fromBase64ToSet(context0) === Set()

      val (ids1, context1) = filter.take(List(1L, 2L, 3L), Some(context0), limit = 2)(idFunc)
      ids1 === List(1, 2)
      coder.fromBase64ToSet(context1) === Set(1, 2)

      val (ids2, context2) = filter.take(List(1L, 2L, 3L, 4L, 5L, 6L), Some(context1), limit = 2)(idFunc)
      ids2 === List(3, 4)
      coder.fromBase64ToSet(context2) === Set(1, 2, 3, 4)

    }
  }

}
