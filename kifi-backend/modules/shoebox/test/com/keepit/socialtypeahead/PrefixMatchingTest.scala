package com.keepit.socialtypeahead

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._

class PrefixMatchingTest extends Specification {

  val names = Seq(
      "Alan Turing",
      "Alan Kay",
      "John McCarthy",
      "Paul McCartney",
      "John Lennon",
      "Woody Allen",
      "Allen Weiner"
  )

  def prefixMatch(query: String): Seq[String] = {
    var ordinal = 0
    names.map{ name =>
      ordinal += 1
      (name, PrefixMatching.distance(name, query).toDouble + 1.0d - (1.0d/ordinal))
    }.collect{
      case (name, score) if score < 1000000.0d => (name, score)
    }.toSeq
    .sortBy(_._2)
    .map(_._1)
  }

  "PrefixMatching" should {
    "filter candidates" in {

      prefixMatch("a") === Seq("Alan Turing", "Alan Kay", "Allen Weiner", "Woody Allen")
      prefixMatch("al") === Seq("Alan Turing", "Alan Kay", "Allen Weiner", "Woody Allen")
      prefixMatch("all") === Seq("Allen Weiner", "Woody Allen")

      prefixMatch("j") === Seq("John McCarthy", "John Lennon")

      prefixMatch("m") === Seq("John McCarthy", "Paul McCartney")
      prefixMatch("mccart") === Seq("John McCarthy", "Paul McCartney")
      prefixMatch("mccarth") === Seq("John McCarthy")
      prefixMatch("mccartn") === Seq("Paul McCartney")

      prefixMatch("a w") === Seq("Allen Weiner", "Woody Allen")
      prefixMatch("w a") === Seq("Woody Allen", "Allen Weiner")

      prefixMatch("a k") === Seq("Alan Kay")
      prefixMatch("k a") === Seq("Alan Kay")
      prefixMatch("a x") === Seq()
      prefixMatch("x a") === Seq()
    }
  }
}