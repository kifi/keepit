package com.keepit.search.query

import com.keepit.search.index.Searcher
import com.keepit.search.index.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import java.io.StringReader
import org.apache.lucene.index.Term
import com.keepit.search.SemanticVector
import scala.math.sqrt

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

  private def innerProd(v: Array[Float], w: Array[Float]): Float = (v zip w).foldLeft(0f){case (s, (x, y)) => s + (x*y)}

  private def cosineDistance(v: Array[Float], w: Array[Float]): Float = {
    val prod = innerProd(v, w)
    val (vNorm, wNorm) = (sqrt(innerProd(v, v)), sqrt(innerProd(w, w)))
    if (vNorm == 0 || wNorm == 0) 0f else prod/(vNorm * wNorm).toFloat
  }


  def leaveOneOut(queryText: String, stem: Boolean, useSketch: Boolean): Set[(Set[Term], Float)] = {
    val terms = getTerms(queryText, stem)
    if (useSketch) {
      val completeSketch = searcher.getSemanticVectorSketch(terms)
      terms.map { t => terms - t }.map { subTerms =>
        val subSketch = searcher.getSemanticVectorSketch(subTerms)
        (subTerms, cosineDistance(subSketch.vec, completeSketch.vec))
      }
    } else {
      val completeVector = searcher.getSemanticVector(terms)
      terms.map { t => terms - t }.map { subTerms =>
        val subVector = searcher.getSemanticVector(subTerms)
        (subTerms, completeVector.similarity(subVector))
      }
    }
  }

  def allSubsets(queryText: String, stem: Boolean, useSketch: Boolean): Set[(Set[Term], Float)] = {
     val terms = getTerms(queryText, stem)
    if (useSketch) {
      val completeSketch = searcher.getSemanticVectorSketch(terms)
      terms.subsets.toSet.filter(!_.isEmpty) map { subTerms: Set[Term] =>
        val subSketch = searcher.getSemanticVectorSketch(subTerms)
        (subTerms, cosineDistance(subSketch.vec, completeSketch.vec))
      }
    } else {
      val completeVector = searcher.getSemanticVector(terms)
      terms.subsets.toSet.filter(! _.isEmpty) map { subTerms: Set[Term] =>
        val subVector = searcher.getSemanticVector(subTerms)
        (subTerms, completeVector.similarity(subVector))
      }
    }
  }
}
