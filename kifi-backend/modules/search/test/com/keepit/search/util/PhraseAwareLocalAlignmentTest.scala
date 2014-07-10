package com.keepit.search.util

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import scala.util.Random

import LocalAlignment._

class PhraseAwareLocalAlignmentTest extends Specification {
  val toId: Map[String, Int] = Map("a" -> 10, "b" -> 20, "c" -> 30, "d" -> 40, "e" -> 50)

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
  val dict4 = Seq(
    (Seq("a") map toId, PhraseMatch(0, 1)),
    (Seq("e") map toId, PhraseMatch(0, 1))
  )

  val pm0 = new PhraseMatcher(dict0)
  val pm1 = new PhraseMatcher(dict1)
  val pm2 = new PhraseMatcher(dict2)
  val pm3 = new PhraseMatcher(dict3)
  val pm4 = new PhraseMatcher(dict4)

  class DummyLocalAlignment extends LocalAlignment {
    var positions = Set.empty[(Int, Int, Float)]
    def begin(): Unit = { positions = Set.empty[(Int, Int, Float)] }
    def update(id: Int, pos: Int, weight: Float = 1.0f): Unit = { positions += ((id, pos, weight)) }
    def end(): Unit = {}
    def score: Float = 0.0f
    def maxScore: Float = 1.0f
  }

  "PhraseAwareLocalAlignment" should {
    "emit positions with weight when not included in phrases" in {
      val dummy = new DummyLocalAlignment
      val doc = Seq("b", "a", "b", "c", "d", "e")

      var localAlignment = new PhraseAwareLocalAlignment(pm0, 0.0f, dummy, 0.5f)
      localAlignment.begin()
      doc.zipWithIndex.foreach { case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 0, 0.5f), (toId("a"), 1, 0.5f), (toId("b"), 2, 1.0f), (toId("c"), 3, 1.0f), (toId("d"), 4, 1.0f), (toId("e"), 5, 0.5f))

      localAlignment = new PhraseAwareLocalAlignment(pm1, 0.0f, dummy, 0.5f)
      localAlignment.begin()
      doc.zipWithIndex.foreach { case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 0, 0.5f), (toId("a"), 1, 0.5f), (toId("b"), 2, 1.0f), (toId("c"), 3, 1.0f), (toId("d"), 4, 1.0f), (toId("e"), 5, 1.0f))

      localAlignment = new PhraseAwareLocalAlignment(pm2, 0.0f, dummy, 0.5f)
      localAlignment.begin()
      doc.zipWithIndex.foreach { case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 0, 0.5f), (toId("a"), 1, 1.0f), (toId("b"), 2, 1.0f), (toId("c"), 3, 0.5f), (toId("d"), 4, 1.0f), (toId("e"), 5, 1.0f))

      localAlignment = new PhraseAwareLocalAlignment(pm3, 0.0f, dummy, 0.5f)
      localAlignment.begin()
      doc.zipWithIndex.foreach { case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 0, 0.5f), (toId("a"), 1, 0.5f), (toId("b"), 2, 1.0f), (toId("c"), 3, 1.0f), (toId("d"), 4, 1.0f), (toId("e"), 5, 1.0f))

      localAlignment = new PhraseAwareLocalAlignment(pm4, 0.0f, dummy, 0.5f)
      localAlignment.begin()
      doc.zipWithIndex.foreach { case (t, i) => localAlignment.update(toId(t), i) }
      localAlignment.end()
      dummy.positions === Set((toId("b"), 0, 0.5f), (toId("a"), 1, 1.0f), (toId("b"), 2, 0.5f), (toId("c"), 3, 0.5f), (toId("d"), 4, 0.5f), (toId("e"), 5, 1.0f))
    }
  }
}

