package com.keepit.search.index.user

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.search.user.{ DeprecatedUserSearchFilterFactory, DeprecatedUserSearcher, DeprecatedUserQueryParser }
import com.keepit.search.index.{ VolatileIndexDirectory, IndexDirectory, DefaultAnalyzer }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.test._
import org.specs2.mutable._
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.util.IdFilterCompressor
import com.keepit.typeahead.PrefixFilter
import com.keepit.common.mail.EmailAddress

import scala.concurrent.Await
import scala.concurrent.duration._

class UserIndexerTest extends Specification with CommonTestInjector {
  private def setup(implicit client: FakeShoeboxServiceClientImpl) = {
    val users = (0 until 4).map { i =>
      UserFactory.user().withId(i + 1).withName(s"firstName${i}", s"lastName${i}").withPictureName(s"picName${i}").withUsername("test").get
    } :+ UserFactory.user().withId(5).withName("Woody", "Allen").withPictureName("face").withUsername("test").get

    val usersWithId = client.saveUsers(users: _*)

    val emails = (0 until 4).map { i => usersWithId(i).id.get -> EmailAddress(s"user${i}@42go.com") } ++ Seq(
      usersWithId(4).id.get -> EmailAddress("woody@fox.com"),
      usersWithId(4).id.get -> EmailAddress("Woody.Allen@GMAIL.com")
    )

    client.addEmails(emails: _*)

    val exps = Seq(UserExperiment(userId = usersWithId(0).id.get, experimentType = ExperimentType("admin")),
      UserExperiment(userId = usersWithId(0).id.get, experimentType = ExperimentType("can_connect")),
      UserExperiment(userId = usersWithId(0).id.get, experimentType = ExperimentType("can message all users")),
      UserExperiment(userId = usersWithId(1).id.get, experimentType = ExperimentType("fake")),
      UserExperiment(userId = usersWithId(2).id.get, experimentType = ExperimentType("admin"))
    )
    exps.foreach { client.saveUserExperiment(_) }

    usersWithId
  }

  def mkUserIndexer(dir: IndexDirectory = new VolatileIndexDirectory, airbrake: AirbrakeNotifier, shoebox: ShoeboxServiceClient): UserIndexer = {
    new UserIndexer(dir, shoebox, airbrake)
  }

  "UserIndxer" should {
    "persist sequence number" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        val updates = Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
        indexer.sequenceNumber.value === 5

        val newUsers = client.saveUsers(UserFactory.user().withName("abc", "xyz").withUsername("test").get)
        client.addEmails(newUsers(0).id.get -> EmailAddress("abc@xyz.com"))
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
        indexer.sequenceNumber.value === 6
      }
    }
  }

  "search users by name prefix" in {
    withInjector(FakeShoeboxServiceModule()) { implicit injector =>
      val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
      setup(client)
      val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
      Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
      val searcher = indexer.getSearcher
      val analyzer = DefaultAnalyzer.defaultAnalyzer
      val parser = new DeprecatedUserQueryParser(analyzer)
      var query = parser.parse("wood")
      searcher.searchAll(query.get).seq.size === 1
      query = parser.parse("     woody      all    ")
      searcher.searchAll(query.get).seq.size === 1

      query = parser.parse("allen")
      searcher.searchAll(query.get).seq.size === 1

      query = parser.parse("firstNaM")
      searcher.searchAll(query.get).seq.size === 4

    }
  }

  "search user by exact email address" in {
    withInjector(FakeShoeboxServiceModule()) { implicit injector =>
      val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
      setup(client)
      val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
      Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
      val searcher = indexer.getSearcher
      val analyzer = DefaultAnalyzer.defaultAnalyzer
      val parser = new DeprecatedUserQueryParser(analyzer)
      val query = parser.parse("woody.allen@gmail.com")
      searcher.searchAll(query.get).seq.size === 1
      searcher.searchAll(query.get).seq.head.id === 5
      val query2 = parser.parse("user1@42go.com")
      searcher.searchAll(query2.get).seq.size === 1
    }

    "store and retreive correct info" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
        indexer.numDocs === 5

        val searcher = new DeprecatedUserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new DeprecatedUserQueryParser(analyzer)
        val query = parser.parse("woody.allen@gmail.com")
        val filterFactory = inject[DeprecatedUserSearchFilterFactory]
        val filter = filterFactory.default(None)
        val hits = searcher.search(query.get, maxHit = 5, searchFilter = filter).hits
        hits.size === 1
        hits(0).basicUser.firstName === "Woody"

        val query2 = parser.parse("firstNa")
        val hits2 = searcher.search(query2.get, 5, filter).hits
        hits2.size === 4
        hits2.map { _.basicUser.firstName } === (0 to 3).map { i => s"firstName${i}" }.toArray
        hits2.map { _.id.id }.seq === (1 to 4)
      }
    }

    "paginate" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
        indexer.numDocs === 5

        val searcher = new DeprecatedUserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new DeprecatedUserQueryParser(analyzer)
        val query = parser.parse("firstNa")

        var context = ""
        var idfilter = IdFilterCompressor.fromBase64ToSet(context)
        val filterFactory = inject[DeprecatedUserSearchFilterFactory]
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
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        val users = setup(client)
        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))
        indexer.numDocs === 5

        client.saveUsers(users(0).withState(UserStates.INACTIVE))
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))

        val searcher = new DeprecatedUserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new DeprecatedUserQueryParser(analyzer)
        val query = parser.parse("firstNa")
        val filterFactory = inject[DeprecatedUserSearchFilterFactory]
        val filter = filterFactory.default(None)
        val hits = searcher.search(query.get, maxHit = 10, searchFilter = filter).hits
        hits.size === 3
      }
    }

    "filter by user experiments" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        setup(client)
        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))

        val searcher = indexer.getSearcher
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new DeprecatedUserQueryParser(analyzer)
        var query = parser.parseWithUserExperimentConstrains("firstNa", Seq())

        searcher.searchAll(query.get).seq.size === 4

        query = parser.parseWithUserExperimentConstrains("firstNa", Seq("admin"))
        searcher.searchAll(query.get).seq.size === 2

        query = parser.parseWithUserExperimentConstrains("firstNa", Seq("can_connect"))
        searcher.searchAll(query.get).seq.size === 3

        query = parser.parseWithUserExperimentConstrains("firstNa", Seq("fake"))
        searcher.searchAll(query.get).seq.size === 3
      }
    }

    "search using prefix field" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        client.saveUsers(
          UserFactory.user().withName("abc", "def").withUsername("test").get,
          UserFactory.user().withName("def", "abc").withUsername("test").get,
          UserFactory.user().withName("uvw", "xyzzzzzzzz").withUsername("test").get,
          UserFactory.user().withName("xyzzzzzzzzzzzzz", "uvw").withUsername("test").get
        )

        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))

        val searcher = new DeprecatedUserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new DeprecatedUserQueryParser(analyzer)
        val filterFactory = inject[DeprecatedUserSearchFilterFactory]
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
        queryTerms = PrefixFilter.tokenize("xyzzzzzzzzzzz") // longer than UserIndexer.PREFIX_MAX_LEN, should do additional filtering
        hits = searcher.search(query.get, 10, filter, queryTerms)
        hits.hits.size === 1
        hits.hits(0).basicUser.firstName === "xyzzzzzzzzzzzzz"
      }
    }

    "search using prefix field when first name or last name have multiple tokens" in {
      withInjector(FakeShoeboxServiceModule()) { implicit injector =>
        val client = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        client.saveUsers(
          UserFactory.user().withName("abc", "def ghi").withUsername("test").get,
          UserFactory.user().withName("abc", "xyz").withUsername("test").get
        )

        val indexer = mkUserIndexer(airbrake = inject[AirbrakeNotifier], shoebox = client)
        Await.result(indexer.asyncUpdate(), Duration(5, SECONDS))

        val searcher = new DeprecatedUserSearcher(indexer.getSearcher)
        val analyzer = DefaultAnalyzer.defaultAnalyzer
        val parser = new DeprecatedUserQueryParser(analyzer)
        val filterFactory = inject[DeprecatedUserSearchFilterFactory]
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
