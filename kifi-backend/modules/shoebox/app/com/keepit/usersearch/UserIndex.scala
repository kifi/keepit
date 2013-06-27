package com.keepit.usersearch

import com.keepit.common.db.Id
import com.keepit.model.User
import com.google.inject.{Inject, Singleton}
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.util.CharArraySet
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import java.io.StringReader

// a simple search for user names. an almost vanilla lucene app.
// no persistence, in-memory index
// lucene standard analyzer, no stopwords
// doing prefix match

object UserIndex {

  val emptyResult = Seq.empty[Id[User]]

  val luceneVersion = Version.LUCENE_41

  val textFieldType = {
    val ft = new FieldType(TextField.TYPE_NOT_STORED)
    ft.setOmitNorms(true)
    ft
  }
  val idFieldType: FieldType = {
    new FieldType(StringField.TYPE_NOT_STORED)
  }

  val userIdFieldName = "id"
  val userIdDocValFieldName = "idVal"
  val userNameFieldName = "name"
}

@Singleton
class UserIndex {
  import UserIndex._

  private[this] val analyzer = new StandardAnalyzer(luceneVersion, CharArraySet.EMPTY_SET)
  private[this] val indexWriterConfig = new IndexWriterConfig(luceneVersion, analyzer)
  private[this] val indexDirectory = new RAMDirectory()
  private[this] val indexWriter = new IndexWriter(indexDirectory, indexWriterConfig)
  private[this] var indexSearcher: Option[IndexSearcher] = None


  def addUser(user: User) = addUsers(Seq(user))

  def addUsers(users: Seq[User]): Unit = synchronized {
    users.foreach{ user =>
      val doc = new Document()

      val userIdField = new Field(userIdFieldName, user.id.get.id.toString, idFieldType)
      doc.add(userIdField)

      val userIdDocValField = new NumericDocValuesField(userIdDocValFieldName, user.id.get.id)
      doc.add(userIdDocValField)

      val userNameField = new Field(userNameFieldName, user.firstName + " " + user.lastName, textFieldType)
      doc.add(userNameField)

      indexWriter.updateDocument(new Term(userIdFieldName, user.id.get.id.toString), doc)
    }
    indexWriter.commit()

    // refresh the searcher
    indexSearcher = indexSearcher.map{ oldIndexSearcher =>
      val indexReader = DirectoryReader.openIfChanged(oldIndexSearcher.getIndexReader.asInstanceOf[DirectoryReader])
      if (indexReader != null) {
        new IndexSearcher(indexReader)
      } else {
        oldIndexSearcher
      }
    }
  }

  def search(query: String): Seq[Id[User]] = {
    (parse(query) map search).getOrElse(emptyResult)
  }

  def search(query: Query): Seq[Id[User]] = {
    val result = new ArrayBuffer[Id[User]]

    indexSearcher.map{ indexSearcher =>
      val rewrittenQuery = indexSearcher.rewrite(query)
      if (rewrittenQuery != null) {
        val weight = indexSearcher.createNormalizedWeight(rewrittenQuery)
        if(weight != null) {
          indexSearcher.getIndexReader.getContext.leaves.foreach{ subReaderContext =>
            val subReader = subReaderContext.reader
            val scorer = weight.scorer(subReaderContext, true, false, subReader.getLiveDocs)
            if (scorer != null) {
              val userIdDocVals = subReader.getNumericDocValues(userIdDocValFieldName)
              var doc = scorer.nextDoc()
              while (doc != NO_MORE_DOCS) {
                val id = userIdDocVals.get(doc)
                result += Id[User](id)
                doc = scorer.nextDoc()
              }
            }
          }
        }
      }
    }
    result
  }

  def parse(query: String): Option[Query] = {  // not using lucene parser
    val ts = analyzer.tokenStream(userNameFieldName, new StringReader(query))
    ts.reset()

    val termAttr = ts.getAttribute(classOf[CharTermAttribute])

    val booleanQuery = new BooleanQuery

    while (ts.incrementToken) {
      val termQuery = new PrefixQuery(new Term(userNameFieldName, new String(termAttr.buffer(), 0, termAttr.length())))
      booleanQuery.add(termQuery, Occur.MUST)
    }

    if (booleanQuery.clauses.size > 0) Some(booleanQuery) else None
  }
}