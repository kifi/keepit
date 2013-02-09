package com.keepit.search.phrasedetector

import com.keepit.search.Lang
import com.keepit.search.index.DefaultAnalyzer
import java.io.StringReader
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute

trait Dictionary[K, V] {
  def lookup(key: K): V
}

trait PhraseDictionary extends Dictionary[Seq[String], Boolean]

//+ Experiments
object NaivePhraseDictionary {
  def apply() = {
    val analyzer = DefaultAnalyzer.forIndexingWithStemmer(Lang("en")).getOrElse(DefaultAnalyzer.forIndexing(Lang("en")))
    val phrases = List(
      "public school",
      "private school",
      "high school",
      "family camping",
      "wealth management",
      "investment management",
      "history museum",
      "natural history",
      "project management",
      "software developer",
      "source code",
      "project manager",
      "data science",
      "data analysis",
      "big data"
    ).map{ s =>
      val ts = analyzer.tokenStream("p", new StringReader(s))
      val termAttr = ts.getAttribute(classOf[CharTermAttribute])

      def getToken() = if (ts.incrementToken()) new String(termAttr.buffer(), 0, termAttr.length()) else ""

      Seq(getToken(), getToken()) // two term phrase only - this is a quick experiment
    }.toSet
    new NaivePhraseDictionary(phrases)
  }
}

class NaivePhraseDictionary(phrases: Set[Seq[String]]) extends PhraseDictionary {
  def lookup(key: Seq[String]) = phrases.contains(key)
}
//- Experiments
