package com.keepit.search.user

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable._
import play.api.Play.current
import play.api.test.Helpers._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.util.Version
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.Term
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.google.inject.{Inject}
import collection.JavaConversions._


class UserIndexerTest extends Specification with ApplicationInjector {
  private def setup(implicit client: FakeShoeboxServiceClientImpl) = {
    val users = (0 until 4).map{ i =>
      User(firstName = s"firstName${i}", lastName = s"lastName${i}", pictureName = Some(s"picName${i}"))
    } :+ User(firstName = "Woody", lastName = "Allen", pictureName = Some("face"))
    
    val usersWithId = client.saveUsers(users: _*)
    
    val emails = (0 until 4).map{ i =>
      EmailAddress(userId = usersWithId(i).id.get, address = s"user${i}@42go.com")
    } ++ Seq(EmailAddress(userId = usersWithId(4).id.get, address = "woody@fox.com"),
     EmailAddress(userId = usersWithId(4).id.get, address = "Woody.Allen@GMAIL.com"))
    
    client.saveEmails(emails: _*)
    
  }
  
  def mkUserIndexer(dir: RAMDirectory = new RAMDirectory): UserIndexer = {
    new UserIndexer(dir, new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing), inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }
  
  "UserIndxer" should {
    "persist sequence number" in {
      running(new DeprecatedSearchApplication().withShoeboxServiceModule){
        setup(inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl])
        val indexer = mkUserIndexer()
        val updates = indexer.run(100, 100)
        indexer.sequenceNumber.value === 5
      }
    }
  }
  
  "search users by name prefix" in {
    running(new DeprecatedSearchApplication().withShoeboxServiceModule){
      val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
      setup(client)
      val indexer = mkUserIndexer()
      indexer.run(100, 100)
      val searcher = indexer.getSearcher
      val analyzer = DefaultAnalyzer.defaultAnalyzer
      val parser = new UserQueryParser(analyzer)
      var query = parser.parse("wood")
      searcher.search(query.get).seq.size === 1
      query = parser.parse("woody      all")
      searcher.search(query.get).seq.size === 1

      query = parser.parse("firstNa")
      searcher.search(query.get).seq.size === 4
    }
  }
  
  "search user by exact email address" in {
    running(new DeprecatedSearchApplication().withShoeboxServiceModule){
      val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
      setup(client)
      val indexer = mkUserIndexer()
      indexer.run()
      val searcher = indexer.getSearcher
      val analyzer = DefaultAnalyzer.defaultAnalyzer
      val parser = new UserQueryParser(analyzer)
      val query = parser.parse("woody.allen@gmail.com")
      searcher.search(query.get).seq.size === 1
      searcher.search(query.get).seq.head.id === 5
      val query2 = parser.parse("user1@42go.com")
      searcher.search(query2.get).seq.size === 1
    }
    
    "store and retreive correct info" in {
      running(new DeprecatedSearchApplication().withShoeboxServiceModule){
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer()
        indexer.run()
        indexer.numDocs === 5
        val searcher = indexer.getSearcher
       
        val doc = searcher.search(new TermQuery(new Term("_ID", 1.toString))).seq.head
        doc.id === 1
        searcher.doc(1).getField("_ID") === null      // why?
      }
    }
  }
  
}
