package com.keepit.search.user

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.search.index.{VolatileIndexDirectory, IndexDirectory, DefaultAnalyzer}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.inject._
import com.keepit.test._
import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.util.IdFilterCompressor
import com.keepit.typeahead.PrefixFilter


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

    val exps = Seq( UserExperiment(userId = usersWithId(0).id.get, experimentType = ExperimentType("admin")),
        UserExperiment(userId = usersWithId(0).id.get, experimentType = ExperimentType("can_connect")),
        UserExperiment(userId = usersWithId(0).id.get, experimentType = ExperimentType("can message all users")),
        UserExperiment(userId = usersWithId(1).id.get, experimentType = ExperimentType("fake")),
        UserExperiment(userId = usersWithId(2).id.get, experimentType = ExperimentType("admin"))
    )
    exps.foreach{client.saveUserExperiment(_)}

    client.saveEmails(emails: _*)
    usersWithId
  }

  def filterFactory = inject[UserSearchFilterFactory]

  def mkUserIndexer(dir: IndexDirectory = new VolatileIndexDirectory): UserIndexer = {
    new UserIndexer(dir, inject[AirbrakeNotifier], inject[ShoeboxServiceClient])
  }

  "UserIndxer" should {
    "persist sequence number" in {
      running(new TestApplication(FakeShoeboxServiceModule())){
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer()
        val updates = indexer.update()
        indexer.sequenceNumber.value === 5

        val newUsers = client.saveUsers(User(firstName = "abc", lastName = "xyz"))
        client.saveEmails(EmailAddress(userId = newUsers(0).id.get, address = "abc@xyz.com"))
        indexer.update()
        indexer.sequenceNumber.value === 6
      }
    }
  }

  "search users by name prefix" in {
    running(new TestApplication(FakeShoeboxServiceModule())){
      val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
      setup(client)
      val indexer = mkUserIndexer()
      indexer.update()
      val searcher = indexer.getSearcher
      val analyzer = DefaultAnalyzer.defaultAnalyzer
      val parser = new UserQueryParser(analyzer)
      var query = parser.parse("wood")
      searcher.search(query.get).seq.size === 1
      query = parser.parse("     woody      all    ")
      searcher.search(query.get).seq.size === 1

      query = parser.parse("allen")
      searcher.search(query.get).seq.size === 1

      query = parser.parse("firstNaM")
      searcher.search(query.get).seq.size === 4

    }
  }

  "search user by exact email address" in {
    running(new TestApplication(FakeShoeboxServiceModule())){
      val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
      setup(client)
      val indexer = mkUserIndexer()
      indexer.update()
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
      running(new TestApplication(FakeShoeboxServiceModule())){
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer()
        indexer.update()
        indexer.numDocs === 5

        val searcher = new UserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new UserQueryParser(analyzer)
        val query = parser.parse("woody.allen@gmail.com")
        val filter = filterFactory.default(None)
        val hits = searcher.search(query.get, maxHit = 5, searchFilter = filter).hits
        hits.size === 1
        hits(0).basicUser.firstName === "Woody"

        val query2 = parser.parse("firstNa")
        val hits2 = searcher.search(query2.get, 5, filter).hits
        hits2.size === 4
        hits2.map{_.basicUser.firstName} === (0 to 3).map{ i => s"firstName${i}"}.toArray
        hits2.map{_.id.id}.seq === (1 to 4)
      }
    }

    "paginate" in {
      running(new TestApplication(FakeShoeboxServiceModule())){
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer()
        indexer.update()
        indexer.numDocs === 5

        val searcher = new UserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new UserQueryParser(analyzer)
        val query = parser.parse("firstNa")

        var context = ""
        var idfilter = IdFilterCompressor.fromBase64ToSet(context)
        var filter = filterFactory.default(None, Some(context), excludeSelf = false)
        var res = searcher.search(query.get, maxHit = 2, searchFilter = filter)
        res.hits.size === 2
        res.hits.map(_.id.id).seq === (1 to 2)

        context = res.context
        idfilter = IdFilterCompressor.fromBase64ToSet(context)
        filter = filterFactory.default(None, Some(context), excludeSelf = false)
        res = searcher.search(query.get, maxHit = 10, searchFilter = filter)
        res.hits.size === 2
        res.hits.map(_.id.id).seq === (3 to 4)
      }
    }

    "keep track of deleted users" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = mkUserIndexer()
        indexer.update()
        indexer.numDocs === 5

        client.saveUsers(users(0).withState(UserStates.INACTIVE))
        indexer.update()

        val searcher = new UserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new UserQueryParser(analyzer)
        val query = parser.parse("firstNa")
        val filter = filterFactory.default(None)
        val hits = searcher.search(query.get, maxHit = 10, searchFilter = filter).hits
        hits.size === 3
      }
    }

    "filter by user experiments" in {
      running(new TestApplication(FakeShoeboxServiceModule())) {
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer()
        indexer.update()

        val searcher = indexer.getSearcher
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new UserQueryParser(analyzer)
        var query = parser.parseWithUserExperimentConstrains("firstNa", Seq())

        searcher.search(query.get).seq.size === 4

        query = parser.parseWithUserExperimentConstrains("firstNa", Seq("admin"))
        searcher.search(query.get).seq.size === 2

        query = parser.parseWithUserExperimentConstrains("firstNa", Seq("can_connect"))
        searcher.search(query.get).seq.size === 3

        query = parser.parseWithUserExperimentConstrains("firstNa", Seq("fake"))
        searcher.search(query.get).seq.size === 3
      }
    }

    "search using prefix field" in {
      running(new TestApplication(FakeShoeboxServiceModule())){
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        client.saveUsers(
          User(firstName = s"abc", lastName = s"def"),
          User(firstName = s"def", lastName = s"abc"),
          User(firstName = s"uvw", lastName = s"xyzzzzzzzz"),
          User(firstName = s"xyzzzzzzzzzzzzz", lastName = s"uvw")
        )

        val indexer = mkUserIndexer()
        indexer.update()

        val searcher = new UserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new UserQueryParser(analyzer)
        val filter = filterFactory.default(None)
        var query = parser.parseWithUserExperimentConstrains("ab de", Seq(), useLucenePrefixQuery = false)
        var queryTerms = PrefixFilter.tokenize("ab de")

        var hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 2
        hits.hits(0).basicUser.firstName === "abc"
        hits.hits(1).basicUser.firstName === "def"

        query = parser.parseWithUserExperimentConstrains("de    ab", Seq(), useLucenePrefixQuery = false)
        queryTerms = PrefixFilter.tokenize("de    ab")
        hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 2
        hits.hits(0).basicUser.firstName === "def"
        hits.hits(1).basicUser.firstName === "abc"

        query = parser.parseWithUserExperimentConstrains("xyzzzzzzz u", Seq(), useLucenePrefixQuery = false)
        queryTerms = PrefixFilter.tokenize("xyzzzzzzz u")
        hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 2
        hits.hits(0).basicUser.firstName === "xyzzzzzzzzzzzzz"
        hits.hits(1).basicUser.firstName === "uvw"

        query = parser.parseWithUserExperimentConstrains("xyzzzzzzzzzzz", Seq(), useLucenePrefixQuery = false)
        queryTerms = PrefixFilter.tokenize("xyzzzzzzzzzzz")               // longer than UserIndexer.PREFIX_MAX_LEN, should do additional filtering
        hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 1
        hits.hits(0).basicUser.firstName === "xyzzzzzzzzzzzzz"
      }
    }

    "search using prefix field when first name or last name have multiple tokens" in {
      running(new TestApplication(FakeShoeboxServiceModule())){
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        client.saveUsers(
          User(firstName = s"abc", lastName = s"def ghi"),
          User(firstName = s"abc", lastName = s"xyz")
        )

        val indexer = mkUserIndexer()
        indexer.update()

        val searcher = new UserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new UserQueryParser(analyzer)
        val filter = filterFactory.default(None)
        var query = parser.parseWithUserExperimentConstrains("ab gh", Seq(), useLucenePrefixQuery = false)
        var queryTerms = PrefixFilter.tokenize("ab gh")

        var hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 1
        hits.hits(0).basicUser.lastName === "def ghi"

        query = parser.parseWithUserExperimentConstrains("ab de", Seq(), useLucenePrefixQuery = false)
        queryTerms = PrefixFilter.tokenize("ab de")

        hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 1
        hits.hits(0).basicUser.lastName === "def ghi"

        query = parser.parseWithUserExperimentConstrains("ab gh de", Seq(), useLucenePrefixQuery = false)
        queryTerms = PrefixFilter.tokenize("ab gh de")

        hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 1
        hits.hits(0).basicUser.lastName === "def ghi"

      }
    }
  }
}
