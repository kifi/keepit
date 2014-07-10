package com.keepit.typeahead

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

  def prefixMatch(names: Seq[String], query: String): Seq[String] = {
    var ordinal = 0
    names.map { name =>
      ordinal += 1
      (name, PrefixMatching.distance(name, query).toDouble + 1.0d - (1.0d / ordinal))
    }.collect {
      case (name, score) if score < 1000000.0d => (name, score)
    }.toSeq
      .sortBy(_._2)
      .map(_._1)
  }

  "PrefixMatching" should {
    "filter candidates" in {

      prefixMatch(names, "a") === Seq("Alan Turing", "Alan Kay", "Allen Weiner", "Woody Allen")
      prefixMatch(names, "al") === Seq("Alan Turing", "Alan Kay", "Allen Weiner", "Woody Allen")
      prefixMatch(names, "all") === Seq("Allen Weiner", "Woody Allen")

      prefixMatch(names, "j") === Seq("John McCarthy", "John Lennon")

      prefixMatch(names, "m") === Seq("John McCarthy", "Paul McCartney")
      prefixMatch(names, "mccart") === Seq("John McCarthy", "Paul McCartney")
      prefixMatch(names, "mccarth") === Seq("John McCarthy")
      prefixMatch(names, "mccartn") === Seq("Paul McCartney")

      prefixMatch(names, "a w") === Seq("Allen Weiner", "Woody Allen")
      prefixMatch(names, "w a") === Seq("Woody Allen", "Allen Weiner")

      prefixMatch(names, "a k") === Seq("Alan Kay")
      prefixMatch(names, "k a") === Seq("Alan Kay")
      prefixMatch(names, "a x") === Seq()
      prefixMatch(names, "x a") === Seq()
    }

    "sort by matching position" in {
      val names = Seq(
        "Eishay Smith",
        "Sam Sasaki",
        "Satoshi Ueno"
      )
      prefixMatch(names, "s") === Seq("Sam Sasaki", "Satoshi Ueno", "Eishay Smith")
    }
  }
}
