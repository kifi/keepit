package com.keepit.search.query

import org.apache.lucene.search.Explanation
import scala.collection.mutable.Queue


object LuceneScoreNames {
  val MULTIPLICATIVE_BOOST = "multiplicative boost"
  val ADDITIVE_BOOST = "additive boost"
  val PERCENT_MATCH= "percentMatch"
  val SEMANTIC_VECTOR = "semantic vector"
  val PHRASE_PROXIMITY = "phrase proximity"

  val allNames = Set(MULTIPLICATIVE_BOOST,
      ADDITIVE_BOOST, PERCENT_MATCH, SEMANTIC_VECTOR, PHRASE_PROXIMITY)
}

/**
 * Original Lucene Explanation object is a tree node. Each node contains a description (score's name) and a value (the score)
 * We extract relevant nodes' values, store them in a 'flat' object.
 */
object LuceneExplanationExtractor {
  // NOTE: currently this excludes some of Lucene's scores, since their descriptions are not available.
  // Also, we don't really want "term level" information, such as weight, idf, termFreq, etc. This is because we
  // would like to perform machine learning algorithms on these scores, and term level information makes the
  // dimension of the feature vector dependent on query's length.

  val namedScores = LuceneScoreNames.allNames

  private def extractName(description: String) = {
    namedScores.find(name => description != null && description.contains(name))
  }

  // NOTE: returned map's size varies. i.e. some scores may not be available for a particular query
  def extractNamedScores(e: Explanation) = {
    var namedScores = Map.empty[String, Float]
    if (e != null) {
      val queue = Queue.empty[Explanation]
      queue += e
      while (queue.size > 0) {
        val node = queue.dequeue()
        extractName(node.getDescription()).foreach { name =>
          // if we have seen this namedScore before, overwrite it if current value is bigger.
          // This makes sense for DisjunctionMaxQuery. Since we don't have many named scores
          // in the tree, this is not a problem for now.
          // TODO: make this more robust

          val s = namedScores.getOrElse(name, 0.0f) max node.getValue
          namedScores += name -> s
        }
        if (node.getDetails() != null) {
          queue ++= node.getDetails.filter(_ != null)
        }
      }
    }
    namedScores
  }
}
