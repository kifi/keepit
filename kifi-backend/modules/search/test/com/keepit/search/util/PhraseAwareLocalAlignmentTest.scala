package com.keepit.search.util

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.util.Random

import LocalAlignment._

class PhraseAwareLocalAlignmentTest extends Specification {
  val toId: Map[String, Int] = Map("a"->10, "b"->20, "c"->30, "d"->40, "e"->50)

  val dict0 = Seq(
    (Seq("b", "c") map toId, PhraseMatch(0, 2)),
    (Seq("c", "d") map toId, PhraseMatch(0, 2))
  )
  val dict1 = Seq(
    (Seq("b", "c") map toId, PhraseMatch(0, 2)),
    (Seq("d", "e") map toId, PhraseMatch(0, 2))
  )
  val dict2 = Seq(
    (Seq("a", "b") map toId, PhraseMatch(0, 2)),
    (Seq("d", "e") map toId, PhraseMatch(0, 2))
  )
  val dict3 = Seq(
    (Seq("b", "c", "d") map toId, PhraseMatch(0, 3)),
    (Seq("c", "d", "e") map toId, PhraseMatch(0, 3))
  )

  val pm0 = new PhraseMatcher(dict0)
  val pm1 = new PhraseMatcher(dict1)
  val pm2 = new PhraseMatcher(dict2)
  val pm3 = new PhraseMatcher(dict3)

  class DummyLocalAlignment extends LocalAlignment {
    var positions = Set.empty[(Int, Int)]
    def begin(): Unit = { positions = Set.empty[(Int, Int)] }
    def update(id: Int, pos: Int): Unit = { positions += ((id, pos)) }
    def end(): Unit = {}
    def score: Float = 0.0f
    def maxScore: Float = 1.0f
  }

  "PhraseAwareLocalAlignment" should {
    "emit positions only when included in phrases" in {
      val dummy = new DummyLocalAlignment
      val doc = Seq("b", "a", "b", "c", "d", "e")

      var localAlignment = new PhraseAwareLocalAlignment(pm0, 0.0f, dummy)
      localAlignment.begin()
      doc.zipWithIndex.foreach{ case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 2), (toId("c"), 3), (toId("d"), 4))

      localAlignment = new PhraseAwareLocalAlignment(pm1, 0.0f, dummy)
      localAlignment.begin()
      doc.zipWithIndex.foreach{ case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 2), (toId("c"), 3), (toId("d"), 4), (toId("e"), 5))

      localAlignment = new PhraseAwareLocalAlignment(pm2, 0.0f, dummy)
      localAlignment.begin()
      doc.zipWithIndex.foreach{ case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("a"), 1), (toId("b"), 2), (toId("d"), 4), (toId("e"), 5))

      localAlignment = new PhraseAwareLocalAlignment(pm3, 0.0f, dummy)
      localAlignment.begin()
      doc.zipWithIndex.foreach{ case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 2), (toId("c"), 3), (toId("d"), 4), (toId("e"), 5))
    }
  }
}

