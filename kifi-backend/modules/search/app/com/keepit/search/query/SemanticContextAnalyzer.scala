package com.keepit.search.query

import com.keepit.search.index.Searcher
import com.keepit.search.index.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.io.StringReader
import org.apache.lucene.index.Term

class SemanticContextAnalyzer(searcher: Searcher, analyzer: Analyzer, stemAnalyzer: Analyzer) {

  private def getTerms(queryText: String, stem: Boolean): Set[Term] = {
    val a = if (stem) stemAnalyzer else analyzer
    val ts = a.tokenStream("sv", new StringReader(queryText))
    val ta = ts.addAttribute(classOf[CharTermAttribute])
    var s = Set.empty[Term]
    ts.reset
    while(ts.incrementToken()){
      s += new Term("sv", ta.toString)
    }
    s
  }

  def leaveOneOut(queryText: String, stem: Boolean): Set[(Set[Term], Float)] = {
    val terms = getTerms(queryText, stem)
    val completeVector = searcher.getSemanticVector(terms)
    terms.map{ t => terms - t}.map{ subTerms =>
      val subVector = searcher.getSemanticVector(subTerms)
      (subTerms, completeVector.similarity(subVector))
    }
  }

}