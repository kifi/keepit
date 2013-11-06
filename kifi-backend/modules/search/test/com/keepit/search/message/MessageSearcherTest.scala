package com.keepit.search.message


import com.keepit.test.TestInjector
import com.keepit.social.BasicUser
import com.keepit.common.db.{Id,ExternalId, SequenceNumber}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.search.index.{DefaultAnalyzer, Indexable, VolatileIndexDirectoryImpl}
import com.keepit.search.Lang
import com.keepit.eliza.FakeElizaServiceClientImpl

import com.google.inject.Injector

import org.apache.lucene.store.RAMDirectory
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.util.Version

import play.api.libs.json.Json

import org.specs2.mutable._


class MessageSearcherTest extends Specification with TestInjector{

  def setupIndexer()(implicit injector: Injector) = {

    val user1 = BasicUser(ExternalId(), "Mr", "Spock", "0.jpg")
    val user2 = BasicUser(ExternalId(), "James", "Kirk", "0.jpg")
    val user3 = BasicUser(ExternalId(), "Jean-Luc", "Picard", "0.jpg")

    val thread1 = ThreadContent(
      mode =  FULL,
      id = Id[ThreadContent](1),
      seq = SequenceNumber(1), 
      participants = Seq(user1, user2),
      updatedAt = currentDateTime,
      url = "http://theenterprise.com",
      threadExternalId = ExternalId().id,
      pageTitleOpt = Some("cheese"),
      digest = "This is thread 1.",
      content = Seq(
        "Hey, how is life on the illogical side of things?",
        "Pretty good. Can't complain.",
        "Good to hear."
      ),
      participantIds = Seq(Id[User](1), Id[User](2)) 
    )

    val thread2 = ThreadContent(
      mode =  FULL,
      id = Id[ThreadContent](2),
      seq = SequenceNumber(2),  
      participants = Seq(user1, user2),
      updatedAt = currentDateTime,
      url = "http://thereliant.com",
      threadExternalId = ExternalId().id,
      pageTitleOpt = None,
      digest = "This is thread 2.",
      content = Seq(
        "How's the vulcan doing? Good?",
        "No complaints. Living long and prosper."
      ),
      participantIds = Seq(Id[User](1), Id[User](2)) 
    )

    val thread3 = ThreadContent(
      mode =  FULL,
      id = Id[ThreadContent](3),
      seq = SequenceNumber(3),  
      participants = Seq(user1, user3),
      updatedAt = currentDateTime,
      url = "http://amazon.com",
      threadExternalId = ExternalId().id,
      pageTitleOpt = None,
      digest = "This is thread 3.",
      content = Seq(
        "Good evening Ambassador. How are you feeling?",
        "You are so illogical."
      ),
      participantIds = Seq(Id[User](1), Id[User](3)) 
    )

    val threadIndexable1 = new MessageContentIndexable(
      data = thread1,
      id = thread1.id,
      sequenceNumber = thread1.seq
    )
    val threadIndexable2 = new MessageContentIndexable(
      data = thread2,
      id = thread2.id,
      sequenceNumber = thread2.seq
    )
    val threadIndexable3 = new MessageContentIndexable(
      data = thread3,
      id = thread3.id,
      sequenceNumber = thread3.seq
    )

    val threadIndexableIterable = Seq[Indexable[ThreadContent]](threadIndexable1,threadIndexable2,threadIndexable3)

    val indexer = new MessageIndexer(
      indexDirectory =  new VolatileIndexDirectoryImpl(),
      indexWriterConfig = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.forIndexing),
      eliza = new FakeElizaServiceClientImpl(inject[AirbrakeNotifier]),
      airbrake = inject[AirbrakeNotifier]
    )

    indexer.indexDocuments(threadIndexableIterable.iterator, 1)

    indexer
  }

  "MessageSearcher" should {

    "find and rank correctly" in {
      withInjector(){ implicit injector =>
        val indexer = setupIndexer() 

        val searcher = new MessageSearcher(indexer.getSearcher)

        val parser = new MessageQueryParser(
          DefaultAnalyzer.forParsing(Lang("en")),
          DefaultAnalyzer.forParsingWithStemmer(Lang("en"))
        )

        parser.parse("illogical good").map{ parsedQuery =>
          searcher.search(Id[User](1), parsedQuery).length === 3
          val results = searcher.search(Id[User](2), parsedQuery)
          results.length===2
          val digests = results.map{ result =>
            (result \ "digest").as[String]
          }
          digests(0)==="This is thread 1."
          digests(1)==="This is thread 2."  
        }

        parser.parse("spock").map{ parsedQuery =>
          searcher.search(Id[User](1), parsedQuery).length===3
          searcher.search(Id[User](2), parsedQuery).length===2
        }

        parser.parse("picard").map{ parsedQuery =>
          searcher.search(Id[User](1), parsedQuery).length===1
          searcher.search(Id[User](2), parsedQuery).length===0
        }

        parser.parse("amazon").map{ parsedQuery =>
          searcher.search(Id[User](1), parsedQuery).length===1
        }

        parser.parse("cheese").map{ parsedQuery =>
          searcher.search(Id[User](1), parsedQuery).length===1
        }
      }
      1===1
    }

  }


}
