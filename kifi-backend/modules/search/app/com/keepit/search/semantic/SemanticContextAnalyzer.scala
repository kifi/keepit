package com.keepit.search.semantic

import com.keepit.search.Searcher
import com.keepit.search.index.Analyzer
import java.io.StringReader
import org.apache.lucene.index.Term
import scala.math.sqrt
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute

class SemanticContextAnalyzer(searcher: Searcher, analyzer: Analyzer, stemAnalyzer: Analyzer) {

  private def getTerms(queryText: String, stem: Boolean): Set[Term] = {
    val a = if (stem) stemAnalyzer else analyzer
    val ts = a.tokenStream("sv", new StringReader(queryText))
    val ta = ts.addAttribute(classOf[CharTermAttribute])
    var s = Set.empty[Term]
    try {
      ts.reset
      while (ts.incrementToken()) {
        s += new Term("sv", ta.toString)
      }
      ts.end()
    } finally {
      ts.close()
    }
    s
  }

  private def innerProd(v: Array[Float], w: Array[Float]): Float = (v zip w).foldLeft(0f) { case (s, (x, y)) => s + (x * y) }

  private def cosineDistance(v: Array[Float], w: Array[Float]): Float = {
    val prod = innerProd(v, w)
    val (vNorm, wNorm) = (sqrt(innerProd(v, v)), sqrt(innerProd(w, w)))
    if (vNorm == 0 || wNorm == 0) 0f else prod / (vNorm * wNorm).toFloat
  }

  def similarity(query1: String, query2: String, stem: Boolean): Float = {
    val (terms1, terms2) = (getTerms(query1, stem), getTerms(query2, stem))
    if (terms1.size == 0 || terms2.size == 0) 0f
    else {
      val (v1, v2) = (searcher.getSemanticVector(terms1), searcher.getSemanticVector(terms2))
      v1.similarity(v2)
    }
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
      terms.subsets.toSet.filter(!_.isEmpty) map { subTerms: Set[Term] =>
        val subVector = searcher.getSemanticVector(subTerms)
        (subTerms, completeVector.similarity(subVector))
      }
    }
  }

  def getSemanticVector(query: String): SemanticVector = {
    val terms = getTerms(query, stem = true)
    searcher.getSemanticVector(terms)
  }

  def semanticLoss(query: String): Map[String, Float] = {
    val terms = getTerms(query, stem = true)
    semanticLoss(terms)
  }

  def semanticLoss(terms: Set[Term]): Map[String, Float] = {
    val svTerms = terms.map { t => new Term("sv", t.text) }
    val completeSketch = searcher.getSemanticVectorSketch(svTerms)
    val completeVector = searcher.getSemanticVector(svTerms)
    svTerms.map { term =>
      val subSketch = completeSketch.clone()
      val sketch = searcher.getSemanticVectorSketch(term)
      SemanticVector.updateSketch(subSketch, sketch, -1) // subtract this vector from sum
      val subVector = SemanticVector.vectorize(subSketch)
      (term.text, completeVector.similarity(subVector))
    }.toMap
  }
}
