package com.keepit.rover.tagcloud

import org.specs2.mutable.Specification

class TagCloudUtilsTest extends Specification {

  "WordCountHelper" should {
    "work" in {
      val ts = (1 to 26).flatMap { i => (0 until i).map { j => (64 + i).toChar.toString } }
      val wc = WordCountHelper.wordCount(ts)
      wc("a") === 1
      wc("z") === 26

      WordCountHelper.topKcounts(wc, 3).toList === List(("z", 26), ("y", 25), ("x", 24))
    }
  }

  "NGramHelper" should {
    "chucks correctly" in {
      val txt = "$all work and, no play makes/jack!a dull? boy"
      NGramHelper.getChunks(txt).toList === List("all work and", "no play makes", "jack", "a dull", "boy")
    }

    "generate ngrams correclty from text" in {
      val txt = "ab bc    cd ab bc cd"
      NGramHelper.generateNGrams(txt, 2, thresh = 1).toSet === Set(("ab bc", 2), ("bc cd", 2), ("cd ab", 1))
      NGramHelper.generateNGrams(txt, 2).toSet === Set(("ab bc", 2), ("bc cd", 2))
      NGramHelper.generateNGrams(txt, 3).toSet === Set(("ab bc cd", 2))
    }

    "geneate ngrams for corpus" in {
      val txt = "ab bc cd ab bc cd"
      val corpus = Seq(txt, txt)
      NGramHelper.generateNGramsForCorpus(corpus, 2).toSet === Set(("ab bc", 4), ("bc cd", 4))
      NGramHelper.generateNGramsForCorpus(corpus, 3).toSet === Set(("ab bc cd", 4))
    }

    "validate ngram" in {
      NGramHelper.isValidGrams("google and".split(" ")) === false
      NGramHelper.isValidGrams("google apple google".split(" ")) === false
      NGramHelper.isValidGrams("google glass".split(" ")) === true
    }

    "build multigram index" in {
      val grams = Set("jack nicolson", "jack london", "london bridge")
      val index = NGramHelper.buildMultiGramIndex(grams)
      index("jack") === Set("jack nicolson", "jack london")
      index("london") === Set("jack london", "london bridge")
      index("bridge") === Set("london bridge")
      index("nicolson") === Set("jack nicolson")
    }

    "combine grams correctly" in {
      val twoGrams = Map("partial differential" -> 10, "partial derivative" -> 20, "implicit derivative" -> 10)
      val threeGrams = Map("partial differential equation" -> 8, "partial derivative exists" -> 5)
      NGramHelper.combineGrams(twoGrams, threeGrams) === Map("partial differential equation" -> 8, "partial derivative" -> 20, "implicit derivative" -> 10)
    }
  }

}
