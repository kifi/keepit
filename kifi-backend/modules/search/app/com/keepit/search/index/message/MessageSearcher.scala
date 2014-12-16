package com.keepit.search.index.message

import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.search.SearchConfig
import com.keepit.search.engine.query.ConditionalQuery
import com.keepit.common.strings.UTF8
import com.keepit.search.index.Searcher

import org.apache.lucene.index.Term
import org.apache.lucene.search.{ Query, TermQuery }
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.BytesRef

import play.api.libs.json.{ Json, JsValue, JsString }

import scala.collection.mutable.ArrayBuffer
import com.keepit.common.time._

case class ResultWithScore(score: Float, value: String)

class MessageSearcher(searcher: Searcher, config: SearchConfig, clock: Clock) {
  private val halfLifeMillis = config.asFloat("messageHalfLifeHours") * (60.0f * 60.0f * 1000.0f) // hours to millis

  //not super effcient. we'll see how it behaves -Stephen
  def search(userId: Id[User], query: Query, from: Int = 0, howMany: Int = 20): Seq[JsValue] = {

    val participantFilterQuery = new TermQuery(new Term(ThreadIndexFields.participantIdsField, userId.id.toString))
    val filterdQuery = new ConditionalQuery(query, participantFilterQuery)

    val allResults = ArrayBuffer[ResultWithScore]()
    searcher.search(filterdQuery) { (scorer, reader) =>
      val resultDocVals = reader.getBinaryDocValues(ThreadIndexFields.resultField)
      val timestampDocVals = reader.getNumericDocValues(ThreadIndexFields.updatedAtField)
      var docNumber = scorer.nextDoc()
      while (docNumber != NO_MORE_DOCS) {
        val resultBytes = resultDocVals.get(scorer.docID())
        val resultString = new String(resultBytes.bytes, resultBytes.offset, resultBytes.length, UTF8)
        val updatedAtMillis = timestampDocVals.get(scorer.docID())
        val timeDecay = Math.exp(-(clock.now().getMillis - updatedAtMillis) / halfLifeMillis).toFloat
        val score = scorer.score() * timeDecay
        allResults.append(
          ResultWithScore(
            score,
            resultString
          )
        )
        docNumber = scorer.nextDoc()
      }
    }

    val orderedResults = allResults.sortWith {
      case (a, b) =>
        a.score > b.score
    }.drop(from).take(howMany)

    orderedResults.map { x =>
      try {
        Json.parse(x.value)
      } catch {
        case _: Throwable => Json.obj(
          "err" -> JsString(x.value)
        )
      }
    }

  }
}
